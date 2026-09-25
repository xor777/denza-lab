/* Fixed, 16-byte aligned arena for the isolated original-code worker.
 * A block stays live until the firmware calls its matching delete entry.
 * No host allocator or system call is used after the worker is isolated.
 */
#ifndef DENZA_BOUNDED_ARENA_H
#define DENZA_BOUNDED_ARENA_H

typedef unsigned char arena_u8;
typedef unsigned int arena_u32;
typedef unsigned long arena_u64;

enum arena_status {
 ARENA_OK, ARENA_BOUND, ARENA_EXHAUSTED, ARENA_INVALID, ARENA_DOUBLE_FREE,
 ARENA_CORRUPT
};

struct arena_block { arena_u32 size, live, tag, reserved; };
struct bounded_arena { arena_u8 *bytes; arena_u32 capacity, end; };

#define ARENA_TAG 0x41524e41U
#define ARENA_HEADER 16U
#define ARENA_ALIGNMENT 16U
#define ARENA_MAX_REQUEST 4096U

static void arena_init(struct bounded_arena *a, void *bytes, arena_u32 capacity) {
 a->bytes=bytes; a->capacity=capacity; a->end=0;
}

static int arena_valid(const struct bounded_arena *a) {
 return a && a->bytes && !((arena_u64)a->bytes%ARENA_ALIGNMENT) &&
        a->capacity>=ARENA_HEADER+ARENA_ALIGNMENT &&
        !(a->capacity%ARENA_ALIGNMENT) && a->end<=a->capacity &&
        !(a->end%ARENA_ALIGNMENT);
}

static enum arena_status arena_block_at(const struct bounded_arena *a,
                                         arena_u32 at, struct arena_block **out) {
 if(!arena_valid(a))return ARENA_CORRUPT;
 if(at>a->end || a->end-at<ARENA_HEADER)return ARENA_CORRUPT;
 struct arena_block *b=(struct arena_block *)(a->bytes+at);
 if(b->tag!=ARENA_TAG || b->reserved || b->live>1 ||
    !b->size || b->size%ARENA_ALIGNMENT ||
    b->size>a->end-at-ARENA_HEADER)return ARENA_CORRUPT;
 *out=b;return ARENA_OK;
}

static void arena_fill(void *address, arena_u8 value, arena_u32 n) {
 arena_u8 *p=address;for(arena_u32 i=0;i<n;i++)p[i]=value;
}

static void *arena_allocate(struct bounded_arena *a, arena_u64 request,
                            enum arena_status *status) {
 if(!arena_valid(a)){*status=ARENA_CORRUPT;return 0;}
 if(!request || request>ARENA_MAX_REQUEST){*status=ARENA_BOUND;return 0;}
 arena_u32 size=((arena_u32)request+ARENA_ALIGNMENT-1)&~(ARENA_ALIGNMENT-1);
 arena_u32 at=0;
 while(at<a->end){
  struct arena_block *b;
  *status=arena_block_at(a,at,&b);if(*status!=ARENA_OK)return 0;
  if(!b->live && b->size>=size){
   arena_u32 excess=b->size-size;
   if(excess>=ARENA_HEADER+ARENA_ALIGNMENT){
    struct arena_block *tail=(struct arena_block *)(a->bytes+at+ARENA_HEADER+size);
    tail->size=excess-ARENA_HEADER;tail->live=0;tail->tag=ARENA_TAG;tail->reserved=0;
    b->size=size;
   }
   b->live=1;arena_fill(a->bytes+at+ARENA_HEADER,0xcc,b->size);
   *status=ARENA_OK;return a->bytes+at+ARENA_HEADER;
  }
  at+=ARENA_HEADER+b->size;
 }
 if(at!=a->end){*status=ARENA_CORRUPT;return 0;}
 if(a->end>a->capacity || a->capacity-a->end<ARENA_HEADER ||
    size>a->capacity-a->end-ARENA_HEADER){*status=ARENA_EXHAUSTED;return 0;}
 struct arena_block *b=(struct arena_block *)(a->bytes+a->end);
 b->size=size;b->live=1;b->tag=ARENA_TAG;b->reserved=0;
 void *result=a->bytes+a->end+ARENA_HEADER;
 a->end+=ARENA_HEADER+size;arena_fill(result,0xcc,size);
 *status=ARENA_OK;return result;
}

static enum arena_status arena_deallocate(struct bounded_arena *a, void *pointer) {
 if(!arena_valid(a))return ARENA_CORRUPT;
 if(!pointer)return ARENA_OK; /* C++ delete on null is valid. */
 arena_u64 base=(arena_u64)a->bytes, p=(arena_u64)pointer;
 if(p<base+ARENA_HEADER ||
    p>=base+a->end || (p-base)%ARENA_ALIGNMENT)return ARENA_INVALID;
 arena_u32 at=0,previous=a->end;
 while(at<a->end){
  struct arena_block *b;
  enum arena_status result=arena_block_at(a,at,&b);
  if(result!=ARENA_OK)return result;
  arena_u32 next=at+ARENA_HEADER+b->size;
  if(p==base+at+ARENA_HEADER){
   if(!b->live)return ARENA_DOUBLE_FREE;
   b->live=0;arena_fill(a->bytes+at+ARENA_HEADER,0xdd,b->size);
   if(next<a->end){
    struct arena_block *following;
    result=arena_block_at(a,next,&following);if(result!=ARENA_OK)return result;
    if(!following->live){b->size+=ARENA_HEADER+following->size;next=at+ARENA_HEADER+b->size;}
   }
   if(previous<a->end){
    struct arena_block *prior;
    result=arena_block_at(a,previous,&prior);if(result!=ARENA_OK)return result;
    if(!prior->live){prior->size+=ARENA_HEADER+b->size;at=previous;}
   }
   if(at+ARENA_HEADER+((struct arena_block *)(a->bytes+at))->size==a->end)
    a->end=at;
   return ARENA_OK;
  }
  previous=at;at=next;
 }
 return ARENA_INVALID;
}
#endif
