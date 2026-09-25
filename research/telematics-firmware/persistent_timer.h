/* Host-clock scheduler for firmware POSIX timers. No clock or thread is
 * created in the isolated engine. The parent supplies monotonic milliseconds.
 * Handles are generation-tagged so a canceled callback cannot be replayed. */
#ifndef PERSISTENT_TIMER_H
#define PERSISTENT_TIMER_H
typedef unsigned long pt_u64;
struct pt_timer {
 pt_u64 handle, deadline_ms, interval_ms, callback, context;
 unsigned generation, active;
};
struct pt_scheduler {
 struct pt_timer slot[6];
 pt_u64 now_ms;
 unsigned next_generation;
};
static int pt_advance(struct pt_scheduler *s,pt_u64 ms){
 if(ms<s->now_ms)return -1;
 s->now_ms=ms;return 0;
}
static int pt_create(struct pt_scheduler *s,unsigned index,pt_u64 callback,pt_u64 context,pt_u64 *handle){
 if(index>=6 || !callback || !handle)return -1;
 struct pt_timer *t=&s->slot[index];
 t->generation=++s->next_generation;
 if(!t->generation)t->generation=++s->next_generation;
 t->handle=((pt_u64)t->generation<<8)|(index+1);
 t->callback=callback;t->context=context;t->active=0;
 *handle=t->handle;return 0;
}
static struct pt_timer *pt_find(struct pt_scheduler *s,pt_u64 handle){
 unsigned index=(unsigned)(handle&255);
 if(!index || index>6)return 0;
 struct pt_timer *t=&s->slot[index-1];
 return t->handle==handle?t:0;
}
static int pt_arm(struct pt_scheduler *s,pt_u64 handle,pt_u64 delay_ms,pt_u64 interval_ms){
 struct pt_timer *t=pt_find(s,handle);
 if(!t || !delay_ms || delay_ms>3600000 || interval_ms>3600000 ||
    s->now_ms>~(pt_u64)0-delay_ms)return -1;
 t->deadline_ms=s->now_ms+delay_ms;t->interval_ms=interval_ms;t->active=1;
 return 0;
}
static int pt_delete(struct pt_scheduler *s,pt_u64 handle){
 struct pt_timer *t=pt_find(s,handle);
 if(!t)return -1;
 t->active=0;t->handle=0;t->callback=0;t->context=0;
 return 0;
}
static int pt_due(struct pt_scheduler *s,unsigned index,pt_u64 *handle,
                  pt_u64 *callback,pt_u64 *context){
 if(index>=6)return 0;
 struct pt_timer *t=&s->slot[index];
 if(!t->active || s->now_ms<t->deadline_ms)return 0;
 *handle=t->handle;*callback=t->callback;*context=t->context;
 if(t->interval_ms){
  pt_u64 jumps=(s->now_ms-t->deadline_ms)/t->interval_ms+1;
  if(jumps>(~(pt_u64)0-t->deadline_ms)/t->interval_ms)t->active=0;
  else t->deadline_ms+=jumps*t->interval_ms;
 }else t->active=0;
 return 1;
}
#endif
