/* Host test of the exact allocator compiled into session_runtime.c. */
#include <assert.h>
#include <stdint.h>
#include "bounded_arena.h"

static unsigned char storage[0x10000] __attribute__((aligned(16)));
static struct bounded_arena a;
static enum arena_status status;

static void *get(unsigned long n) {
 void *p=arena_allocate(&a,n,&status);
 assert(status==ARENA_OK && p && (uintptr_t)p%16==0);
 return p;
}
static void reset(unsigned int capacity) {arena_init(&a,storage,capacity);}

int main(void) {
 reset(sizeof(storage));
 assert(arena_deallocate(&a,0)==ARENA_OK);
 assert(!arena_allocate(&a,0,&status) && status==ARENA_BOUND);
 assert(!arena_allocate(&a,ARENA_MAX_REQUEST+1UL,&status) && status==ARENA_BOUND);
 assert(!arena_allocate(&a,~0UL,&status) && status==ARENA_BOUND);
 /* Largest stock configuration: 255 records, companion array and vector. */
 void *records=get(140*255), *indices=get(1+8*255), *vector=get(24*256);
 assert(arena_deallocate(&a,records)==ARENA_OK);
 assert(arena_deallocate(&a,indices)==ARENA_OK);
 assert(arena_deallocate(&a,vector)==ARENA_OK && a.end==0);

 unsigned char *owner=get(31), *temporary=get(80), *other=get(47);
 for(int i=0;i<31;i++)owner[i]=0x31;
 for(int i=0;i<47;i++)other[i]=0x47;
 assert(arena_deallocate(&a,temporary+1)==ARENA_INVALID);
 assert(arena_deallocate(&a,temporary+16)==ARENA_INVALID);
 assert(arena_deallocate(&a,storage)==ARENA_INVALID);
 assert(arena_deallocate(&a,owner)==ARENA_OK);
 assert(arena_deallocate(&a,owner)==ARENA_DOUBLE_FREE);
 unsigned char *replacement=get(31);
 assert(replacement==owner);
 for(int i=0;i<31;i++)assert(replacement[i]==0xcc);
 for(int i=0;i<47;i++)assert(other[i]==0x47);
 assert(arena_deallocate(&a,temporary)==ARENA_OK);
 assert(arena_deallocate(&a,other)==ARENA_OK);
 for(int i=0;i<31;i++)assert(replacement[i]==0xcc);
 assert(arena_deallocate(&a,replacement)==ARENA_OK);
 assert(a.end==0);

 /* Exhaustion remains bounded, then a freed block is reused. */
 reset(sizeof(storage));
 void *large[15];
 for(int i=0;i<15;i++)large[i]=get(4096);
 assert(!arena_allocate(&a,4096,&status) && status==ARENA_EXHAUSTED);
 assert(arena_deallocate(&a,large[0])==ARENA_OK);
 assert(get(4096)==large[0]);

 /* No adjacent free span: a 128-byte request cannot use two holes. */
 reset(512);
 void *slot[4];for(int i=0;i<4;i++)slot[i]=get(64);
 get(176); /* Fills the remainder exactly. */
 assert(a.end==512);
 assert(arena_deallocate(&a,slot[1])==ARENA_OK);
 assert(arena_deallocate(&a,slot[3])==ARENA_OK);
 assert(!arena_allocate(&a,128,&status) && status==ARENA_EXHAUSTED);
 assert(arena_deallocate(&a,slot[2])==ARENA_OK);
 assert(get(128)==slot[1]); /* Adjacent free blocks coalesced. */

 /* Splitting a large hole preserves another live owner and the remainder. */
 reset(512);
 void *first=get(32), *hole=get(240), *last=get(32);
 ((unsigned char *)first)[0]=0x5a;((unsigned char *)last)[0]=0xa5;
 assert(arena_deallocate(&a,hole)==ARENA_OK);
 assert(get(48)==hole);
 get(128);
 assert(((unsigned char *)first)[0]==0x5a);
 assert(((unsigned char *)last)[0]==0xa5);

 /* Damaged in-band metadata fails closed before reuse. */
 reset(128);
 get(16);
 storage[8]=0;
 assert(!arena_allocate(&a,16,&status) && status==ARENA_CORRUPT);

 /* An untrusted end must be rejected before scanning beyond the array. */
 reset(128);
 a.end=0xfffffff0U;
 assert(!arena_allocate(&a,16,&status) && status==ARENA_CORRUPT);
 assert(arena_deallocate(&a,storage+16)==ARENA_CORRUPT);
 reset(128);
 a.bytes=storage+1;
 assert(!arena_allocate(&a,16,&status) && status==ARENA_CORRUPT);
 return 0;
}
