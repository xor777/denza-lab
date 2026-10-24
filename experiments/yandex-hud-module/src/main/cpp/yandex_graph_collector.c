/* Exact-build, process-local read-only graph tile observer. No vehicle API.
 * No Android NDK dependency: this deliberately uses the small stable libc/JNI ABI.
 * JNI table slots below are independently checked against the installed JDK header
 * by tools/yandex_hud_build/native_graph.py.
 */
typedef unsigned long size_t;
typedef unsigned long uintptr_t;
typedef unsigned long long uint64_t;
typedef unsigned int uint32_t;
typedef unsigned char uint8_t;
typedef long long jlong;
typedef void ***JNIEnv;
typedef void *jobject;

#define EXPORTED __attribute__((visibility("default")))
#define HIDDEN __attribute__((visibility("hidden")))
#define SLOT_COUNT 8
#define MAX_TILE_BYTES (2U * 1024U * 1024U)
#define DECODER_OFFSET 0x298bba0UL
#define PROT_READ 1
#define PROT_WRITE 2
#define PROT_EXEC 4
#define MAP_PRIVATE 2
#define MAP_ANONYMOUS 0x20
#define MAP_FIXED_NOREPLACE 0x100000
#define MAP_FAILED ((void *)-1)

typedef struct { uint32_t type, flags; uintptr_t offset, vaddr, paddr, filesz, memsz, align; } Phdr;
typedef struct { uintptr_t addr; const char *name; const Phdr *phdr; unsigned short phnum; } DlInfo;
extern int dl_iterate_phdr(int (*)(DlInfo *, size_t, void *), void *);
extern void *dlopen(const char *, int);
extern void *mmap(void *, size_t, int, int, int, long);
extern int mprotect(void *, size_t, int);
extern int munmap(void *, size_t);
extern int getpagesize(void);
extern void *memcpy(void *, const void *, size_t);
extern int memcmp(const void *, const void *, size_t);
extern int strcmp(const char *, const char *);
extern void *fopen(const char *, const char *);
extern char *fgets(char *, int, void *);
extern int fclose(void *);
extern const uint8_t denza_graph_relay_begin[], denza_graph_relay_end[];
extern const uint8_t denza_graph_relay_original[], denza_graph_relay_branch[], denza_graph_relay_callback[];
extern void denza_graph_flush_cache(void *, void *);
extern void denza_graph_probe_target(void);
extern void denza_graph_probe_callback(void);
extern void denza_graph_probe_invoke(void *, void *);

enum { EMPTY = 0, WRITING = 1, READY = 2, READING = 3 };
typedef struct {
    uint32_t state;
    uint32_t size;
    uint64_t sequence;
    uint8_t bytes[MAX_TILE_BYTES];
} Slot;
static Slot slots[SLOT_COUNT];
static int native_state, install_lock, install_error;
static int self_test_result = -1;
static uint64_t seen, captured, polled, dropped, invalid, oversize, captured_bytes, sequence;
static void *installed_relay;
static void *pinned_maps_handle;

static uint64_t load64(const uint64_t *v) { return __atomic_load_n(v, __ATOMIC_RELAXED); }
static void add64(uint64_t *v, uint64_t n) { __atomic_fetch_add(v, n, __ATOMIC_RELAXED); }

/* Invoked with the original x0 only. The assembly relay preserves every GPR,
 * SIMD register and FP/status register. This path does no allocation, disk I/O,
 * JNI, locking waits or calls into Navigator. Queue pressure drops observations.
 */
HIDDEN void denza_graph_capture(const uintptr_t *vector) {
    add64(&seen, 1);
    if (!vector) { add64(&invalid, 1); return; }
    uintptr_t begin = vector[0], end = vector[1];
    if (!begin || end <= begin) { add64(&invalid, 1); return; }
    size_t size = end - begin;
    if (size > MAX_TILE_BYTES) { add64(&oversize, 1); return; }
    uint64_t ticket = __atomic_fetch_add(&sequence, 1, __ATOMIC_RELAXED);
    for (unsigned n = 0; n < SLOT_COUNT; ++n) {
        Slot *slot = &slots[(ticket + n) % SLOT_COUNT];
        uint32_t expected = EMPTY;
        if (!__atomic_compare_exchange_n(&slot->state, &expected, WRITING, 0,
                                         __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) continue;
        memcpy(slot->bytes, (const void *)begin, size);
        slot->size = (uint32_t)size;
        slot->sequence = ticket;
        add64(&captured, 1);
        add64(&captured_bytes, size);
        __atomic_store_n(&slot->state, READY, __ATOMIC_RELEASE);
        return;
    }
    add64(&dropped, 1);
}

/* A no-replace request cannot overwrite a mapping. Older kernels may ignore its
 * unknown bit; an ordinary hint also cannot overwrite anything. Accept only the
 * requested address, so either fallback remains safe against allocation races. */
static void *map_gap(uintptr_t candidate, size_t page) {
    for (unsigned mode = 0; mode < 2; ++mode) {
        int flags = MAP_PRIVATE | MAP_ANONYMOUS | (mode ? 0 : MAP_FIXED_NOREPLACE);
        void *p = mmap((void *)candidate, page, PROT_READ | PROT_WRITE, flags, -1, 0);
        if (p == MAP_FAILED) continue;
        if ((uintptr_t)p == candidate) return p;
        munmap(p, page);
    }
    return 0;
}

static int hex_address(const char **cursor, uintptr_t *value) {
    const char *p = *cursor;
    uintptr_t result = 0;
    unsigned digits = 0;
    for (; digits < 16; ++digits, ++p) {
        unsigned digit;
        if (*p >= '0' && *p <= '9') digit = (unsigned)(*p - '0');
        else if (*p >= 'a' && *p <= 'f') digit = (unsigned)(*p - 'a' + 10);
        else if (*p >= 'A' && *p <= 'F') digit = (unsigned)(*p - 'A' + 10);
        else break;
        result = (result << 4) | digit;
    }
    if (!digits) return 0;
    *cursor = p; *value = result;
    return 1;
}

/* Installation-worker I/O only, never the graph callback. Scan actual free gaps
 * within the branch range instead of asking the kernel to honor occupied 2 MiB
 * hints. MAP_FIXED is deliberately never used, including on older kernels. */
static void *map_near(uintptr_t target, size_t page) {
    uintptr_t low = (target - 0x07ff0000UL + page - 1) & ~(uintptr_t)(page - 1);
    uintptr_t high = (target + 0x07ff0000UL) & ~(uintptr_t)(page - 1);
    void *maps = fopen("/proc/self/maps", "r");
    if (maps) {
        char line[512];
        uintptr_t previous = low;
        unsigned lines = 0;
        while (lines++ < 65536 && fgets(line, sizeof(line), maps)) {
            const char *cursor = line;
            uintptr_t begin, end;
            if (!hex_address(&cursor, &begin) || *cursor++ != '-' ||
                !hex_address(&cursor, &end) || (*cursor != ' ' && *cursor != '\t') || end <= begin) continue;
            if (end <= low) continue;
            uintptr_t gap_end = begin < high ? begin : high;
            uintptr_t candidate = (previous + page - 1) & ~(uintptr_t)(page - 1);
            if (gap_end > candidate && gap_end - candidate >= page) {
                void *p = map_gap(candidate, page);
                if (p) { fclose(maps); return p; }
            }
            if (begin >= high) break;
            if (end > previous) previous = end;
            if (previous >= high) break;
        }
        fclose(maps);
    }
    /* Permission-restricted /proc fallback. Requests are still non-destructive,
     * but a kernel that relocates an occupied hint is never accepted here. */
    uintptr_t anchor = target & ~(uintptr_t)(page - 1);
    for (unsigned n = 1; n <= 120; ++n) {
        uintptr_t delta = (uintptr_t)((n + 1) / 2) * 0x200000UL;
        uintptr_t hint = (n & 1) ? anchor + delta : anchor - delta;
        void *p = map_gap(hint, page);
        if (p) return p;
    }
    return 0;
}

static int branch_word(uintptr_t from, uintptr_t to, uint32_t *word) {
    long offset = (long)(to - from);
    if ((offset & 3) || offset < -0x08000000L || offset >= 0x08000000L) return 0;
    *word = 0x14000000U | ((uint32_t)(offset >> 2) & 0x03ffffffU);
    return 1;
}

static void *make_relay(uintptr_t target, uintptr_t callback, size_t page) {
    size_t length = denza_graph_relay_end - denza_graph_relay_begin;
    size_t original = denza_graph_relay_original - denza_graph_relay_begin;
    size_t branch = denza_graph_relay_branch - denza_graph_relay_begin;
    size_t literal = denza_graph_relay_callback - denza_graph_relay_begin;
    if (length > page || original + 4 > length || literal + 8 > length) return 0;
    uint8_t *code = map_near(target, page);
    if (!code) return 0;
    uint32_t back;
    if (!branch_word((uintptr_t)code + branch, target + 4, &back)) {
        munmap(code, page); return 0;
    }
    memcpy(code, denza_graph_relay_begin, length);
    memcpy(code + original, (void *)target, 4);
    memcpy(code + branch, &back, 4);
    memcpy(code + literal, &callback, 8);
    denza_graph_flush_cache(code, code + length);
    if (mprotect(code, page, PROT_READ | PROT_EXEC)) { munmap(code, page); return 0; }
    return code;
}

static const uint8_t build_id[20] = {
    0x92,0x99,0x79,0xda,0xf6,0xef,0x38,0x54,0x13,0xaf,
    0x0e,0x76,0x15,0xe3,0x31,0x9a,0xa4,0x34,0x64,0xbe
};
static const uint8_t decoder_prefix[16] = {
    0xff,0x83,0x07,0xd1,0xfd,0x7b,0x18,0xa9,
    0xfc,0x6f,0x19,0xa9,0xfa,0x67,0x1a,0xa9
};
typedef struct { uintptr_t base; int valid_build, executable_target; char path[1024]; } Module;
static int inspect_module(DlInfo *info, size_t size, void *opaque) {
    (void)size;
    Module *result = opaque;
    if (!info->name) return 0;
    const char *leaf = info->name;
    for (const char *p = leaf; *p; ++p) if (*p == '/') leaf = p + 1;
    if (strcmp(leaf, "libmaps-mobile.so")) return 0;
    result->base = info->addr;
    unsigned path_size = 0;
    while (info->name[path_size] && path_size + 1 < sizeof(result->path)) ++path_size;
    if (info->name[path_size]) return 1; /* Refuse a truncated dlopen identity. */
    memcpy(result->path, info->name, path_size + 1);
    uintptr_t target = info->addr + DECODER_OFFSET;
    for (unsigned i = 0; i < info->phnum; ++i) {
        const Phdr *p = &info->phdr[i];
        if (p->type == 1 && (p->flags & 1) && target >= info->addr + p->vaddr &&
            target + 16 <= info->addr + p->vaddr + p->memsz) result->executable_target = 1;
        if (p->type != 4 || p->memsz > 65536) continue;
        const uint8_t *n = (const uint8_t *)(info->addr + p->vaddr);
        size_t offset = 0;
        while (offset + 12 <= p->memsz) {
            const uint32_t *header = (const uint32_t *)(n + offset);
            size_t name = (header[0] + 3UL) & ~3UL;
            size_t desc = (header[1] + 3UL) & ~3UL;
            if (name > p->memsz || desc > p->memsz || offset + 12 + name + desc > p->memsz) break;
            if (header[2] == 3 && header[0] == 4 && header[1] == 20 &&
                !memcmp(n + offset + 12, "GNU", 4) &&
                !memcmp(n + offset + 12 + name, build_id, 20)) result->valid_build = 1;
            offset += 12 + name + desc;
        }
    }
    return 1;
}

/* The synthetic target uses the same first instruction as the pinned decoder.
 * Its callback intentionally destroys registers, SIMD and FP state. The resumed
 * original body snapshots state and returns through the original LR.
 */
static int run_self_test(size_t page) {
    union { uint64_t words[104]; uint8_t bytes[832]; } snapshot = {{0}};
    uintptr_t target = (uintptr_t)&denza_graph_probe_target;
    void *relay = make_relay(target, (uintptr_t)&denza_graph_probe_callback, page);
    if (!relay) return 1;
    denza_graph_probe_invoke(relay, &snapshot);
    int failures = 0;
    if (snapshot.words[0] != (uintptr_t)&snapshot) failures |= 2;
    for (unsigned i = 1; i <= 29; ++i) {
        uint64_t expected = i == 16 ? (uintptr_t)relay : 0x100U + i;
        if (snapshot.words[i] != expected) failures |= 4;
    }
    if (snapshot.words[30] != snapshot.words[100]) failures |= 8;
    if (snapshot.words[31] != 0xa0000000U || snapshot.words[32] != 0x01000000U ||
        snapshot.words[33] != 0x08000000U) failures |= 16;
    for (unsigned i = 0; i < 32; ++i)
        for (unsigned j = 0; j < 16; ++j)
            if (snapshot.bytes[0x110 + i * 16 + j] != i) failures |= 32;
    if (snapshot.words[98] != snapshot.words[99]) failures |= 64;
    /* Synchronous private self-test only: no entry point references this relay. */
    munmap(relay, page);
    return failures;
}

EXPORTED int Java_dev_denza_hud_probe_NativeGraphCollector_nativeStart(JNIEnv env, jobject type) {
    (void)env; (void)type;
    int state = __atomic_load_n(&native_state, __ATOMIC_ACQUIRE);
    if (state > 0) return state;
    int expected = 0;
    if (!__atomic_compare_exchange_n(&install_lock, &expected, 1, 0,
                                     __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) return -9;
    int result = -1;
    size_t page = (size_t)getpagesize();
    Module module = {0};
    if ((page != 4096 && page != 16384 && page != 65536)) { result = -10; goto done; }
    if (__atomic_load_n(&self_test_result, __ATOMIC_RELAXED) < 0)
        __atomic_store_n(&self_test_result, run_self_test(page), __ATOMIC_RELAXED);
    if (__atomic_load_n(&self_test_result, __ATOMIC_RELAXED)) { result = -11; goto done; }
    dl_iterate_phdr(inspect_module, &module);
    if (!module.base) { result = -2; goto done; }
    if (!module.valid_build || !module.executable_target) { result = -3; goto done; }
    /* Pin the actual observed absolute/APK path, including libraries extracted
     * outside the classloader's usual search path. NOLOAD never initializes a new
     * image. A disappearing image fails this step; an acquired handle remains. */
    if (!pinned_maps_handle) pinned_maps_handle = dlopen(module.path, 2 | 4);
    if (!pinned_maps_handle) { result = -2; goto done; }
    Module confirmed = {0};
    dl_iterate_phdr(inspect_module, &confirmed);
    if (confirmed.base != module.base || !confirmed.valid_build || !confirmed.executable_target) {
        result = -3; goto done;
    }
    uintptr_t target = module.base + DECODER_OFFSET;
    if (memcmp((void *)target, decoder_prefix, 16)) { result = -4; goto done; }
    void *relay = make_relay(target, (uintptr_t)&denza_graph_capture, page);
    if (!relay) { result = -5; goto done; }
    uint32_t branch;
    if (!branch_word(target, (uintptr_t)relay, &branch)) { munmap(relay, page); result = -5; goto done; }
    void *target_page = (void *)(target & ~(uintptr_t)(page - 1));
    if (mprotect(target_page, page, PROT_READ | PROT_WRITE | PROT_EXEC)) {
        munmap(relay, page); result = -6; goto done;
    }
    uint32_t original;
    memcpy(&original, decoder_prefix, 4);
    if (!__atomic_compare_exchange_n((uint32_t *)target, &original, branch, 0,
                                     __ATOMIC_RELEASE, __ATOMIC_RELAXED)) {
        mprotect(target_page, page, PROT_READ | PROT_EXEC);
        munmap(relay, page); result = -7; goto done;
    }
    installed_relay = relay; /* Process lifetime; never remove an executing hook. */
    denza_graph_flush_cache((void *)target, (void *)(target + 4));
    result = mprotect(target_page, page, PROT_READ | PROT_EXEC) ? 2 : 1;
done:
    __atomic_store_n(&install_error, result < 0 ? result : 0, __ATOMIC_RELAXED);
    __atomic_store_n(&native_state, result, __ATOMIC_RELEASE);
    __atomic_store_n(&install_lock, 0, __ATOMIC_RELEASE);
    return result;
}

EXPORTED jobject Java_dev_denza_hud_probe_NativeGraphCollector_nativePoll(JNIEnv env, jobject type) {
    (void)type;
    Slot *chosen = 0;
    for (unsigned i = 0; i < SLOT_COUNT; ++i) {
        uint32_t expected = READY;
        if (!__atomic_compare_exchange_n(&slots[i].state, &expected, READING, 0,
                                         __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) continue;
        chosen = &slots[i];
        break;
    }
    if (!chosen) return 0;
    jobject array = ((jobject (*)(JNIEnv, int))(*env)[176])(env, chosen->size);
    if (!array) { __atomic_store_n(&chosen->state, READY, __ATOMIC_RELEASE); return 0; }
    ((void (*)(JNIEnv, jobject, int, int, const void *))(*env)[208])
        (env, array, 0, chosen->size, chosen->bytes);
    add64(&polled, 1);
    __atomic_store_n(&chosen->state, EMPTY, __ATOMIC_RELEASE);
    return array;
}

EXPORTED jobject Java_dev_denza_hud_probe_NativeGraphCollector_nativeStats(JNIEnv env, jobject type) {
    (void)type;
    jlong ready = 0;
    for (unsigned i = 0; i < SLOT_COUNT; ++i)
        if (__atomic_load_n(&slots[i].state, __ATOMIC_ACQUIRE) == READY) ++ready;
    jlong values[] = {__atomic_load_n(&native_state, __ATOMIC_ACQUIRE), (jlong)load64(&seen),
        (jlong)load64(&captured), (jlong)load64(&polled), (jlong)load64(&dropped),
        (jlong)load64(&invalid), (jlong)load64(&oversize), (jlong)load64(&captured_bytes),
        ready, __atomic_load_n(&self_test_result, __ATOMIC_RELAXED), SLOT_COUNT, MAX_TILE_BYTES,
        __atomic_load_n(&install_error, __ATOMIC_RELAXED)};
    jobject array = ((jobject (*)(JNIEnv, int))(*env)[180])(env, 13);
    if (array) ((void (*)(JNIEnv, jobject, int, int, const jlong *))(*env)[212])(env, array, 0, 13, values);
    return array;
}
