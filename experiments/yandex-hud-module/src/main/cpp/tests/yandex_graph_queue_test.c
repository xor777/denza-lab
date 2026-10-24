/* Host-only queue pressure/concurrency check. No Navigator or Android execution. */
#define fopen denza_graph_test_fopen
#define fgets denza_graph_test_fgets
#define fclose denza_graph_test_fclose
#include "../yandex_graph_collector.c"
#undef fopen
#undef fgets
#undef fclose
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void *denza_graph_test_fopen(const char *path, const char *mode) { return fopen(path, mode); }
char *denza_graph_test_fgets(char *out, int size, void *file) { return fgets(out, size, file); }
int denza_graph_test_fclose(void *file) { return fclose(file); }

const uint8_t denza_graph_relay_begin[1] = {0}, denza_graph_relay_end[1] = {0};
const uint8_t denza_graph_relay_original[1] = {0}, denza_graph_relay_branch[1] = {0};
const uint8_t denza_graph_relay_callback[1] = {0};
int dl_iterate_phdr(int (*callback)(DlInfo *, size_t, void *), void *opaque) {
    (void)callback; (void)opaque; return 0;
}
void denza_graph_flush_cache(void *begin, void *end) { (void)begin; (void)end; }
void denza_graph_probe_target(void) { }
void denza_graph_probe_callback(void) { }
void denza_graph_probe_invoke(void *relay, void *out) { (void)relay; (void)out; }

typedef struct { int size; uint8_t bytes[]; } Blob;
static jobject new_bytes(JNIEnv env, int size) {
    (void)env;
    Blob *blob = malloc(sizeof(*blob) + size);
    if (!blob) abort();
    blob->size = size;
    return blob;
}
static void set_bytes(JNIEnv env, jobject array, int offset, int size, const void *bytes) {
    (void)env;
    Blob *blob = array;
    if (offset != 0 || size != blob->size) abort();
    memcpy(blob->bytes, bytes, size);
}
static void *jni_table[213];
static void **jni_pointer = jni_table;
static JNIEnv fake_env = &jni_pointer;
static unsigned completed;
static void require(int ok, const char *message) {
    if (!ok) { fprintf(stderr, "%s\n", message); abort(); }
}
static void submit(const void *bytes, size_t size) {
    uintptr_t vector[3] = {(uintptr_t)bytes, (uintptr_t)bytes + size, (uintptr_t)bytes + size};
    denza_graph_capture(vector);
}
static Blob *poll_blob(void) {
    return Java_dev_denza_hud_probe_NativeGraphCollector_nativePoll(fake_env, 0);
}
static void *produce(void *opaque) {
    uint32_t id = (uint32_t)(uintptr_t)opaque;
    for (uint32_t sequence = 0; sequence < 30000; ++sequence) {
        uint32_t payload[16];
        payload[0] = id; payload[1] = sequence;
        for (unsigned i = 2; i < 16; ++i) payload[i] = (sequence ^ id ^ 0xa5a5a5a5U) + i;
        submit(payload, sizeof(payload));
    }
    __atomic_fetch_add(&completed, 1, __ATOMIC_RELEASE);
    return 0;
}
int main(void) {
    jni_table[176] = (void *)new_bytes;
    jni_table[208] = (void *)set_bytes;
    uint8_t fixed[17];
    memset(fixed, 0x6d, sizeof(fixed));
    for (unsigned i = 0; i < SLOT_COUNT + 1; ++i) submit(fixed, sizeof(fixed));
    require(load64(&captured) == SLOT_COUNT && load64(&dropped) == 1, "bounded queue pressure");
    for (unsigned i = 0; i < SLOT_COUNT; ++i) {
        Blob *blob = poll_blob();
        require(blob && blob->size == sizeof(fixed) && !memcmp(blob->bytes, fixed, sizeof(fixed)), "payload identity");
        free(blob);
    }
    require(!poll_blob(), "queue empty");
    uintptr_t invalid_vector[2] = {2, 1}, oversized_vector[2] = {1, MAX_TILE_BYTES + 2UL};
    denza_graph_capture(0); denza_graph_capture(invalid_vector); denza_graph_capture(oversized_vector);
    require(load64(&invalid) == 2 && load64(&oversize) == 1, "invalid/oversize gate before copy");
    uint8_t *maximum = malloc(MAX_TILE_BYTES);
    require(maximum != 0, "maximum allocation");
    memset(maximum, 0xa7, MAX_TILE_BYTES);
    submit(maximum, MAX_TILE_BYTES);
    Blob *full = poll_blob();
    require(full && full->size == MAX_TILE_BYTES && !memcmp(full->bytes, maximum, MAX_TILE_BYTES), "maximum tile identity");
    free(full); free(maximum);
    pthread_t producers[4];
    uint64_t before = load64(&polled);
    for (uintptr_t i = 0; i < 4; ++i) require(!pthread_create(&producers[i], 0, produce, (void *)(i + 1)), "pthread_create");
    for (;;) {
        Blob *blob = poll_blob();
        if (!blob) {
            if (__atomic_load_n(&completed, __ATOMIC_ACQUIRE) == 4) break;
            continue;
        }
        require(blob->size == 64, "concurrent tile size");
        const uint32_t *payload = (const uint32_t *)blob->bytes;
        require(payload[0] >= 1 && payload[0] <= 4 && payload[1] < 30000, "concurrent tile header");
        for (unsigned i = 2; i < 16; ++i)
            require(payload[i] == ((payload[1] ^ payload[0] ^ 0xa5a5a5a5U) + i), "concurrent torn payload");
        free(blob);
    }
    for (unsigned i = 0; i < 4; ++i) pthread_join(producers[i], 0);
    Blob *blob;
    while ((blob = poll_blob())) free(blob);
    require(load64(&captured) == load64(&polled), "accepted tiles drained exactly once");
    require(load64(&polled) > before, "concurrent capture progress");
    require(load64(&seen) == load64(&captured) + load64(&dropped) + load64(&invalid) + load64(&oversize), "all attempts accounted");
    printf("queue stress passed: seen=%llu captured=%llu polled=%llu drops=%llu maxTileBytes=%u slots=%u\n",
           load64(&seen), load64(&captured), load64(&polled), load64(&dropped), MAX_TILE_BYTES, SLOT_COUNT);
    return 0;
}
