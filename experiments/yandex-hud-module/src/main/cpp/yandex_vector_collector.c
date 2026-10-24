/* Passive exact-build vmap3 observer. No JNI, allocation, filesystem, mutex wait,
 * source calls, or vehicle APIs in denza_vector_event. Six one-word hooks leave
 * LR untouched, including C++ exception unwinding. All addresses are ELF VAs. */
typedef unsigned long size_t;
typedef unsigned long uintptr_t;
typedef unsigned long long uint64_t;
typedef unsigned int uint32_t;
typedef unsigned short uint16_t;
typedef unsigned char uint8_t;
typedef long long jlong;
typedef void ***JNIEnv;
typedef void *jobject;
#define EXPORTED __attribute__((visibility("default")))
#define HIDDEN __attribute__((visibility("hidden")))
#define SLOT_COUNT 8
#define MAX_TILE_BYTES (2U * 1024U * 1024U)
#define HEADER_SIZE 72
#define THREAD_COUNT 64
#define FRAME_COUNT 24
extern void *memcpy(void *, const void *, size_t);
extern int memcmp(const void *, const void *, size_t);
enum { EMPTY, WRITING, READY, READING };
enum { LAYER_IN, WRAPPER_IN, DECODER_IN, LAYER_OUT, WRAPPER_OUT, DECODER_OUT };
typedef struct { uint32_t x, y, z; } TileId;
_Static_assert(sizeof(uintptr_t) == 8 && sizeof(TileId) == 12, "Unsupported collector ABI");
typedef struct {
    uint32_t state, size;
    uint64_t sequence;
    uint8_t bytes[HEADER_SIZE + MAX_TILE_BYTES];
} Slot;
typedef struct {
    uintptr_t sp, lr, output;
    unsigned kind, valid;
    int slot;
    TileId tile, source;
} Frame;
typedef struct {
    uintptr_t key;
    unsigned depth, busy;
    Frame frames[FRAME_COUNT];
} Thread;
static Slot slots[SLOT_COUNT];
static Thread threads[THREAD_COUNT];
static int native_state, install_lock, install_error, self_test_result = -1;
static uint64_t seen, captured, polled, dropped, invalid, oversize, captured_bytes, sequence;
static uint64_t parse_failed, mismatched_ids, missing_context, context_overflow, abandoned, reentrant;
static uint64_t layer_seen, wrapper_seen, return_mismatch;
static uint64_t load64(const uint64_t *v) { return __atomic_load_n(v, __ATOMIC_RELAXED); }
static void add64(uint64_t *v, uint64_t n) { __atomic_fetch_add(v, n, __ATOMIC_RELAXED); }
static void put32(uint8_t *p, uint32_t n) {
    for (unsigned i = 0; i < 4; ++i) p[i] = (uint8_t)(n >> (8 * i));
}
static void put64(uint8_t *p, uint64_t n) {
    for (unsigned i = 0; i < 8; ++i) p[i] = (uint8_t)(n >> (8 * i));
}
static void put_tile(uint8_t *p, TileId t) { put32(p, t.x); put32(p + 4, t.y); put32(p + 8, t.z); }
static int tile_valid(TileId t) { return t.z <= 30 && t.x < (1U << t.z) && t.y < (1U << t.z); }
static int tile_equal(TileId a, TileId b) { return a.x == b.x && a.y == b.y && a.z == b.z; }
static int read_tile(uintptr_t p, TileId *tile) {
    if (!p || (p & 3)) return 0;
    memcpy(tile, (const void *)p, sizeof(*tile));
    return tile_valid(*tile);
}
static void discard(Frame *frame) {
    if (frame->slot >= 0) {
        __atomic_store_n(&slots[frame->slot].state, EMPTY, __ATOMIC_RELEASE);
        frame->slot = -1;
    }
}
static Thread *find_thread(uintptr_t key, int create) {
    unsigned first = (unsigned)((key >> 4) % THREAD_COUNT);
    for (unsigned n = 0; n < THREAD_COUNT; ++n) {
        Thread *t = &threads[(first + n) % THREAD_COUNT];
        uintptr_t actual = __atomic_load_n(&t->key, __ATOMIC_ACQUIRE);
        if (actual == key) return t;
        if (!actual && create) {
            uintptr_t expected = 0;
            if (__atomic_compare_exchange_n(&t->key, &expected, key, 0,
                                            __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) return t;
            if (expected == key) return t;
        }
    }
    return 0;
}
static void capture_decoder(Thread *t, Frame *frame, const uintptr_t *regs) {
    Frame *wrapper = 0, *layer = 0;
    /* A new layer nested inside the nearest wrapper invalidates that wrapper's
     * association. Never borrow TileIds across a different rendering context. */
    for (unsigned i = t->depth - 1; i > 0; --i) {
        Frame *p = &t->frames[i - 1];
        if (!wrapper && p->kind == WRAPPER_IN) wrapper = p;
        if (p->kind == LAYER_IN) { layer = p; break; }
    }
    if (!wrapper || !layer || !wrapper->valid || !layer->valid) {
        add64(&missing_context, 1); return;
    }
    if (!tile_equal(wrapper->tile, layer->tile) || !tile_equal(wrapper->tile, layer->source)) {
        add64(&mismatched_ids, 1); return;
    }
    if (!regs[0] || (regs[0] & 7) || !regs[8]) { add64(&invalid, 1); return; }
    uintptr_t range[2];
    memcpy(range, (const void *)regs[0], sizeof(range));
    if (!range[0] || range[1] <= range[0]) { add64(&invalid, 1); return; }
    size_t size = range[1] - range[0];
    if (size > MAX_TILE_BYTES) { add64(&oversize, 1); return; }
    uint64_t ticket = __atomic_fetch_add(&sequence, 1, __ATOMIC_RELAXED);
    for (unsigned n = 0; n < SLOT_COUNT; ++n) {
        unsigned index = (unsigned)((ticket + n) % SLOT_COUNT);
        Slot *slot = &slots[index];
        uint32_t expected = EMPTY;
        if (!__atomic_compare_exchange_n(&slot->state, &expected, WRITING, 0,
                                         __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) continue;
        slot->size = (uint32_t)size + HEADER_SIZE;
        slot->sequence = ticket;
        put32(slot->bytes, 0x31545659U); /* YVT1 */
        slot->bytes[4] = 1; slot->bytes[5] = 0;
        slot->bytes[6] = HEADER_SIZE; slot->bytes[7] = 0;
        put32(slot->bytes + 8, (uint32_t)size);
        put32(slot->bytes + 12, 1); /* Only published on successful native parse. */
        put64(slot->bytes + 16, ticket);
        put64(slot->bytes + 24, t->key); /* Opaque TLS identity; not Linux TID. */
        put_tile(slot->bytes + 32, wrapper->tile);
        put_tile(slot->bytes + 44, layer->tile);
        put_tile(slot->bytes + 56, layer->source);
        put32(slot->bytes + 68, 0);
        memcpy(slot->bytes + HEADER_SIZE, (const void *)range[0], size);
        frame->slot = (int)index;
        frame->output = regs[8];
        return;
    }
    add64(&dropped, 1);
}

/* regs points at the assembly's complete saved GPR snapshot. The callback never
 * writes it. SP and x30 are preserved by the relay, never redirected. */
HIDDEN void denza_vector_event(const uintptr_t *regs, unsigned event, uintptr_t sp, uintptr_t key) {
    if (__atomic_load_n(&native_state, __ATOMIC_ACQUIRE) != 1 || event > DECODER_OUT || !key) return;
    int entering = event < LAYER_OUT;
    Thread *t = find_thread(key, entering);
    if (!t) { if (entering) add64(&context_overflow, 1); return; }
    unsigned expected = 0;
    if (!__atomic_compare_exchange_n(&t->busy, &expected, 1, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) {
        add64(&reentrant, 1); return;
    }
    if (entering) {
        /* Normal AArch64 frames grow down. Equal/higher SP means a previous
         * observed frame returned via exception/longjmp rather than its RET. */
        while (t->depth && t->frames[t->depth - 1].sp <= sp) {
            discard(&t->frames[--t->depth]); add64(&abandoned, 1);
        }
        if (t->depth == FRAME_COUNT) {
            /* Permanently stop collecting rather than associate overflowed
             * descendants with an older wrapper. Existing hooks remain inert. */
            add64(&context_overflow, 1);
            __atomic_store_n(&native_state, -12, __ATOMIC_RELEASE);
            goto done;
        }
        Frame *f = &t->frames[t->depth++];
        f->sp = sp; f->lr = regs[30]; f->kind = event; f->valid = 0; f->slot = -1; f->output = 0;
        if (event == LAYER_IN) {
            add64(&layer_seen, 1);
            f->valid = regs[0] && read_tile(regs[0] + 0x18, &f->tile)
                    && read_tile(regs[0] + 0x38, &f->source);
            if (!f->valid) add64(&invalid, 1);
        } else if (event == WRAPPER_IN) {
            add64(&wrapper_seen, 1);
            f->valid = read_tile(regs[0], &f->tile);
            if (!f->valid) add64(&invalid, 1);
        } else {
            add64(&seen, 1);
            capture_decoder(t, f, regs);
        }
    } else {
        while (t->depth && t->frames[t->depth - 1].sp < sp) {
            discard(&t->frames[--t->depth]); add64(&abandoned, 1);
        }
        if (!t->depth) { add64(&return_mismatch, 1); goto done; }
        Frame *f = &t->frames[t->depth - 1];
        if (f->sp != sp || f->lr != regs[30] || f->kind != event - LAYER_OUT) {
            /* A mismatch invalidates all current associations on this thread. */
            while (t->depth) discard(&t->frames[--t->depth]);
            add64(&return_mismatch, 1); goto done;
        }
        if (f->slot >= 0) {
            Slot *slot = &slots[f->slot];
            if (*(const uint8_t *)(f->output + 0x10) == 1) {
                add64(&captured, 1); add64(&captured_bytes, slot->size - HEADER_SIZE);
                __atomic_store_n(&slot->state, READY, __ATOMIC_RELEASE);
                f->slot = -1;
            } else { add64(&parse_failed, 1); discard(f); }
        }
        --t->depth;
    }
done:
    __atomic_store_n(&t->busy, 0, __ATOMIC_RELEASE);
    /* A thread registry entry remains claimed during the original call. When
     * its stack empties it is reusable, so short-lived renderer threads cannot
     * exhaust the registry. No callback ever waits for another thread. */
    if (!t->depth) __atomic_store_n(&t->key, 0, __ATOMIC_RELEASE);
}

#ifndef DENZA_VECTOR_TEST
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
extern int strcmp(const char *, const char *);
extern void *fopen(const char *, const char *);
extern char *fgets(char *, int, void *);
extern int fclose(void *);
extern const uint8_t denza_vector_relay_begin[], denza_vector_relay_end[];
extern const uint8_t denza_vector_relay_original[], denza_vector_relay_branch[];
extern const uint8_t denza_vector_relay_callback[], denza_vector_relay_event[];
extern void denza_vector_flush_cache(void *, void *);
extern void denza_vector_probe_target(void), denza_vector_probe_wrapper(void), denza_vector_probe_layer(void);
extern void denza_vector_probe_ret(void), denza_vector_probe_callback(void);
extern void denza_vector_probe_invoke(void *, void *), denza_vector_probe_invoke_ret(void *, void *);
static void *pinned_maps_handle;
typedef struct { uintptr_t offset; unsigned event; uint8_t prefix[16]; } Hook;
static const Hook hooks[6] = {
    {0x17c1bdc, LAYER_IN, {0xfd,0x7b,0xba,0xa9,0xfc,0x6f,0x01,0xa9,0xfa,0x67,0x02,0xa9,0xf8,0x5f,0x03,0xa9}},
    {0x17bb6c8, WRAPPER_IN, {0xff,0x83,0x01,0xd1,0xfd,0x7b,0x04,0xa9,0xf4,0x4f,0x05,0xa9,0xfd,0x03,0x01,0x91}},
    {0x17bbaa0, DECODER_IN, {0xff,0x43,0x01,0xd1,0xfd,0x7b,0x02,0xa9,0xf6,0x57,0x03,0xa9,0xf4,0x4f,0x04,0xa9}},
    {0x17c25c0, LAYER_OUT, {0xc0,0x03,0x5f,0xd6}},
    {0x17bb748, WRAPPER_OUT, {0xc0,0x03,0x5f,0xd6}},
    {0x17bbb30, DECODER_OUT, {0xc0,0x03,0x5f,0xd6}}
};
static void *installed_relays[6];
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

static void *make_relay(uintptr_t target, uintptr_t callback, unsigned event, size_t page) {
    size_t length = denza_vector_relay_end - denza_vector_relay_begin;
    size_t original = denza_vector_relay_original - denza_vector_relay_begin;
    size_t branch = denza_vector_relay_branch - denza_vector_relay_begin;
    size_t literal = denza_vector_relay_callback - denza_vector_relay_begin;
    size_t event_literal = denza_vector_relay_event - denza_vector_relay_begin;
    if (length > page || original + 4 > length || literal + 8 > length || event_literal + 8 > length) return 0;
    uint8_t *code = map_near(target, page);
    if (!code) return 0;
    uint32_t back;
    if (!branch_word((uintptr_t)code + branch, target + 4, &back)) {
        munmap(code, page); return 0;
    }
    memcpy(code, denza_vector_relay_begin, length);
    memcpy(code + original, (void *)target, 4);
    memcpy(code + branch, &back, 4);
    memcpy(code + literal, &callback, 8);
    uint64_t event_value = event;
    memcpy(code + event_literal, &event_value, 8);
    denza_vector_flush_cache(code, code + length);
    if (mprotect(code, page, PROT_READ | PROT_EXEC)) { munmap(code, page); return 0; }
    return code;
}

static const uint8_t build_id[20] = {
    0x92,0x99,0x79,0xda,0xf6,0xef,0x38,0x54,0x13,0xaf,
    0x0e,0x76,0x15,0xe3,0x31,0x9a,0xa4,0x34,0x64,0xbe
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
    unsigned mapping_count = 0;
    for (unsigned i = 0; i < info->phnum; ++i) {
        const Phdr *p = &info->phdr[i];
        if (p->type == 1 && (p->flags & 5) == 5) {
            for (unsigned h = 0; h < 6; ++h) {
                uintptr_t target = info->addr + hooks[h].offset;
                if (target >= info->addr + p->vaddr && target + 16 <= info->addr + p->vaddr + p->memsz)
                    mapping_count |= 1U << h;
            }
        }
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
    result->executable_target = mapping_count == 63;
    return 1;
}

/* Run all four actually relocated instruction forms, with the callback
 * deliberately destroying every GPR/SIMD/FP state. Refuse installation on any
 * mismatch. This executes only private synthetic code, never Yandex functions. */
static int run_self_test(size_t page) {
    uintptr_t targets[] = {(uintptr_t)&denza_vector_probe_target, (uintptr_t)&denza_vector_probe_wrapper,
                           (uintptr_t)&denza_vector_probe_layer, (uintptr_t)&denza_vector_probe_ret};
    int failures = 0;
    for (unsigned test = 0; test < 4; ++test) {
        union { uint64_t words[104]; uint8_t bytes[832]; } snapshot = {{0}};
        void *relay = make_relay(targets[test], (uintptr_t)&denza_vector_probe_callback, 0, page);
        if (!relay) return 1;
        if (test == 3) denza_vector_probe_invoke_ret(relay, &snapshot);
        else denza_vector_probe_invoke(relay, &snapshot);
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
        munmap(relay, page); /* No installed entry ever referenced private test. */
    }
    return failures;
}

EXPORTED int Java_dev_denza_hud_probe_NativeVectorCollector_nativeStart(JNIEnv env, jobject type) {
    (void)env; (void)type;
    int state = __atomic_load_n(&native_state, __ATOMIC_ACQUIRE);
    if (state == 1 || (state < 0 && state != -2)) return state;
    int expected = 0;
    if (!__atomic_compare_exchange_n(&install_lock, &expected, 1, 0,
                                     __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) return -9;
    int result = -1;
    size_t page = (size_t)getpagesize();
    Module module = {0};
    void *relays[6] = {0};
    unsigned patched = 0;
    if (page != 4096 && page != 16384 && page != 65536) { result = -10; goto done; }
    __atomic_store_n(&self_test_result, run_self_test(page), __ATOMIC_RELAXED);
    if (__atomic_load_n(&self_test_result, __ATOMIC_RELAXED)) { result = -11; goto done; }
    dl_iterate_phdr(inspect_module, &module);
    if (!module.base) { result = -2; goto done; }
    if (!module.valid_build || !module.executable_target) { result = -3; goto done; }
    if (!pinned_maps_handle) pinned_maps_handle = dlopen(module.path, 2 | 4);
    if (!pinned_maps_handle) { result = -2; goto done; }
    Module confirmed = {0};
    dl_iterate_phdr(inspect_module, &confirmed);
    if (confirmed.base != module.base || !confirmed.valid_build || !confirmed.executable_target) {
        result = -3; goto done;
    }
    for (unsigned h = 0; h < 6; ++h) {
        uintptr_t target = module.base + hooks[h].offset;
        size_t prefix_size = h < 3 ? 16 : 4;
        if (memcmp((void *)target, hooks[h].prefix, prefix_size)) { result = -4; goto done; }
        relays[h] = make_relay(target, (uintptr_t)&denza_vector_event, hooks[h].event, page);
        if (!relays[h]) { result = -5; goto done; }
    }
    /* Observations stay disabled throughout installation, so a renderer crossing
     * a partially installed set cannot publish unpaired context. Each patched
     * instruction is aligned and installed atomically; original LR is untouched.
     * On failure the already-visible relays must live until process exit even
     * after rollback: another thread may currently be executing one. */
    for (unsigned h = 0; h < 6; ++h) {
        uintptr_t target = module.base + hooks[h].offset;
        void *target_page = (void *)(target & ~(uintptr_t)(page - 1));
        uint32_t branch, original;
        if (!branch_word(target, (uintptr_t)relays[h], &branch)) { result = -5; goto rollback; }
        if (mprotect(target_page, page, PROT_READ | PROT_WRITE | PROT_EXEC)) { result = -6; goto rollback; }
        memcpy(&original, hooks[h].prefix, 4);
        if (!__atomic_compare_exchange_n((uint32_t *)target, &original, branch, 0,
                                         __ATOMIC_RELEASE, __ATOMIC_RELAXED)) {
            mprotect(target_page, page, PROT_READ | PROT_EXEC); result = -7; goto rollback;
        }
        ++patched;
        installed_relays[h] = relays[h];
        denza_vector_flush_cache((void *)target, (void *)(target + 4));
        if (mprotect(target_page, page, PROT_READ | PROT_EXEC)) { result = -8; goto rollback; }
    }
    result = 1;
    goto done;
rollback:
    for (unsigned h = 0; h < patched; ++h) {
        uintptr_t target = module.base + hooks[h].offset;
        void *target_page = (void *)(target & ~(uintptr_t)(page - 1));
        uint32_t branch, original;
        memcpy(&original, hooks[h].prefix, 4);
        if (!branch_word(target, (uintptr_t)relays[h], &branch)) continue;
        if (mprotect(target_page, page, PROT_READ | PROT_WRITE | PROT_EXEC)) continue;
        __atomic_compare_exchange_n((uint32_t *)target, &branch, original, 0,
                                    __ATOMIC_RELEASE, __ATOMIC_RELAXED);
        denza_vector_flush_cache((void *)target, (void *)(target + 4));
        mprotect(target_page, page, PROT_READ | PROT_EXEC);
    }
done:
    for (unsigned h = patched; h < 6; ++h) if (relays[h]) munmap(relays[h], page);
    __atomic_store_n(&install_error, result < 0 ? result : 0, __ATOMIC_RELAXED);
    __atomic_store_n(&native_state, result, __ATOMIC_RELEASE);
    __atomic_store_n(&install_lock, 0, __ATOMIC_RELEASE);
    return result;
}
EXPORTED jobject Java_dev_denza_hud_probe_NativeVectorCollector_nativePoll(JNIEnv env, jobject type) {
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

EXPORTED jobject Java_dev_denza_hud_probe_NativeVectorCollector_nativeStats(JNIEnv env, jobject type) {
    (void)type;
    jlong ready = 0;
    for (unsigned i = 0; i < SLOT_COUNT; ++i)
        if (__atomic_load_n(&slots[i].state, __ATOMIC_ACQUIRE) == READY) ++ready;
    jlong values[] = {__atomic_load_n(&native_state, __ATOMIC_ACQUIRE), (jlong)load64(&seen),
        (jlong)load64(&captured), (jlong)load64(&polled), (jlong)load64(&dropped),
        (jlong)load64(&invalid), (jlong)load64(&oversize), (jlong)load64(&captured_bytes),
        ready, __atomic_load_n(&self_test_result, __ATOMIC_RELAXED), SLOT_COUNT, MAX_TILE_BYTES,
        __atomic_load_n(&install_error, __ATOMIC_RELAXED), (jlong)load64(&parse_failed),
        (jlong)load64(&mismatched_ids), (jlong)load64(&missing_context), (jlong)load64(&context_overflow),
        (jlong)load64(&abandoned), (jlong)load64(&reentrant), (jlong)load64(&layer_seen),
        (jlong)load64(&wrapper_seen), (jlong)load64(&return_mismatch)};
    jobject array = ((jobject (*)(JNIEnv, int))(*env)[180])(env, 22);
    if (array) ((void (*)(JNIEnv, jobject, int, int, const jlong *))(*env)[212])(env, array, 0, 22, values);
    return array;
}
#endif
