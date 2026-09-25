/* Persistent isolated engine, pinned to firmware SHA-256 9e36cdbf...82eb9.
 * One process owns one frozen identity and one network session. A new session
 * requires a new process; parent owns the process lease, TLS and SDK.
 *
 * Bounded ASCII IPC (one serialized parent writer; hex is lowercase):
 *   READY, then CAPS PROTO2=1 REG=1 DATA=1 CONTROL532=0 WAKE536=0 MCU_STATE=0
 *                    POSIX_TIMERS=0 SUB5_TIMER=0 POST_LOGIN=0 HEARTBEAT=0
 *                    CONTROL_AWAKE=0 WAKE_ACK_AWAKE=0 TIMERS_AWAKE=0
 *                    POST_LOGIN_AWAKE=0
 *                    STOCK_LIFECYCLE_BRIDGE=0
 *   OP <id:u32> <epoch:u32> <verb> [argument]
 *   identity/startup verbs before START: V,K,U,C,M,S,A,T,N, all hex. A is the
 *     isolated firmware's virtual PUBLIC transport profile `double_apn`,
 *     independent of the actual stock modem APN retained by the parent.
 *   START; NETSTATE <signed virtual public network state> calls original
 *     notify_nw(0x4b694); this path is experimental and still fails closed
 *     in the SDK readiness continuation. G/D/L (native frame producers);
 *     R211/R200/R220/R511 (native RX);
 *   I <opaque SDK buffer hex>; H <charging byte LE hex>;
 *   E <36-byte hex> before each RX/MCU: LE u32 ACC, MCU state, speed,
 *     1023/0x2f4000fa vehicle mode, SOC IEEE754 bits, repair_mode,
 *     energytype, AC power; final LE u32 validity mask (bit per value).
 *     E supplies native object fields only; original SDK getters use CALL.
 *   X <16-byte hex>: LE u32 vehicle_40d_code, record_610_upload,
 *     unlock_index, validity mask. Compatibility fixture; native property
 *     reads and integer getters below use synchronous CALL, not X defaults.
 *   RX <complete TLS frame hex>; MCU <opaque SDK buffer hex>;
 *   INT <device> <fid decimal> <signed value> executes original observer.
 *     MCU 1005/0x99000003 is tested. ACC/charge enter unclosed lifecycle
 *     code and remain behind MCU_STATE=0.
 *   TICK <elapsedRealtime_ms> <uptimeMillis_ms> <wall_ms>, before each
 *     operation and at an ARM deadline. The engine currently uses the first
 *     clock for relative POSIX delivery; Android suspend semantics still need
 *     qualification. The second is native CLOCK_MONOTONIC for steady_clock
 *     and condition waits; the third supplies time().
 *   RESULT <id> <epoch> WIRE|LOGIN|REG|ENDPOINT|STATUS ...
 *   ARM|CANCEL|FIRED <id> <epoch> <generation handle> [deadline_ms]
 *   NET <id> <epoch> <original command decimal> <native TLS frame hex>
 *   After an actual complete TLS write of that frame, OP <id> <epoch>
 *   SENT <same command> invokes original sender send_complete(0x47d54).
 *   Completions are FIFO and an RX for an uncompleted matching send aborts.
 *   AUTO <id> <epoch> <FID decimal> <native SDK bytes hex>
 *   DONE <id> <epoch> <verb or decoded command> [reply count terminal flag]
 *   CALL <id> <epoch> GET_INT <device> <fid decimal>;
 *     RET <id> <epoch> VALUE <signed decimal>
 *   CALL <id> <epoch> GET_FLOAT <device> <fid decimal>;
 *     RET <id> <epoch> VALUE <raw IEEE754 u32 decimal>
 *   CALL <id> <epoch> GET_BUFFER <device> <fid decimal>;
 *     RET <id> <epoch> BUFFER <signed native status> <hex bytes or ->.
 *     Success copies at most 512 opaque SDK bytes into native-owned memory;
 *     failure must carry the actual SDK status and `-`.
 *   CALL <id> <epoch> DNS_LOOKUP <ASCII hostname hex>;
 *     RET <id> <epoch> IPV4 <1..4 network-order 8-hex addresses>, or
 *     IPV4 - only for an actual empty result. IPv6/mixed/error aborts.
 *     Original 0x52ea0 owns the DNS decision and iterates the hostent list.
 *   CALL <id> <epoch> PROPERTY_GET <key hex>;
 *     RET <id> <epoch> VALUE <ASCII hex or - for empty>
 *   CALL <id> <epoch> PROPERTY_SET_RESULT <key hex> <value hex or ->;
 *     RET <id> <epoch> VALUE <0|-1>. Session-owned latches are local;
 *     unsupported shared writes return -1 to the original caller.
 *     Qualified shared effects return success only after the actual operation.
 *   CALL <id> <epoch> PROPERTY_SET_NULL <key hex> for an original null
 *     `property_set` value; live bridge must prove its platform semantics.
 *   CALL <id> <epoch> PROPERTY_SET_STATUS <key hex> <value hex>;
 *     RET <id> <epoch> VALUE <0|-1>. Only the optional edge configuration
 *     publication uses this path. The awake adapter denies that global write
 *     with -1; original firmware decides how to continue after refusal.
 *   CALL <id> <epoch> MATH_POW <raw double u64> <raw double u64>;
 *     RET <id> <epoch> VALUE <raw double u64>, a standard libm primitive.
 *   CALL <id> <epoch> LOCALTIME <signed Unix seconds>;
 *     RET <id> <epoch> TM <sec> <min> <hour> <mday> <mon0>
 *       <year_since1900> <wday_sun0> <yday0> <isdst>.
 *   CALL <id> <epoch> MKTIME <same nine signed tm fields>;
 *     RET <id> <epoch> VALUE <signed Unix seconds>.
 *   CALL <id> <epoch> SET_INT <device> <fid decimal> <signed value>;
 *     RET <id> <epoch> OK after exact SDK setter succeeds.
 *   CALL <id> <epoch> WAIT <absolute monotonic deadline_ns>;
 *     RET <id> <epoch> TIMEOUT <uptime_ms> <elapsed_ms> <wall_ms>, or
 *     RET <id> <epoch> EVENT 1005 0x99000003-decimal <0|1>
 *       <uptime_ms> <elapsed_ms> <wall_ms> <fresh E36hex> <fresh X16hex>.
 *     EVENT runs original integer observer inside the outer OP. Its effects
 *     retain the outer id/epoch and no nested DONE is emitted.
 *   ERR, mismatched RET or EOF kills this process. sys.tcp_step is a private
 *   engine property, never a shared stock daemon property write.
 *   The observed sys.cloud.remote_controling=1 after sleeping 536 requires
 *   owner cleanup after crash; no live product capability is asserted.
 *
 * Input line <=2099 printable bytes; decoded frame <=1024. Effects are emitted
 * synchronously in original call order. Missing host values, unknown externals or bounds
 * fails closed. CAP=0 paths are research execution only, not product support.
 * RX/MCU payloads stay opaque. No Binder or socket is opened here. */
#define CONTROL_EXPERIMENT 1
typedef unsigned long u64;
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;
extern const u8 native_image_start[], native_image_end[];
#include "session_pages.h"
#include "persistent_relocations.h"
#include "bounded_arena.h"
#include "persistent_timer.h"
static u8 *base;
static u8 object[0x5000] __attribute__((aligned(16)));
static u8 sender_slot[0x1b0] __attribute__((aligned(16)));
static u8 secondary[0x600] __attribute__((aligned(16)));
static u8 observer[0x100] __attribute__((aligned(16)));
/* Local empty SRE sink, not a constructed stock 0x7da8c publisher. The
 * original list lookup observes no subscribers. Publication through the
 * external SRE bus remains explicitly unsupported; RTT payload logging is
 * omitted. This port is separate from the live vehicle SDK subscription. */
static u8 local_sre_empty_sink[0x40] __attribute__((aligned(16)));
static u8 local_sre_sink_ready=1;
/* This listener is local to the detached engine. It implements only the
 * original alarm_start/set_alarm_interval calls made by 0x48280 for REG0;
 * it is never installed as the stock system_server Binder listener. */
static u64 local_alarm_vtable[12] __attribute__((aligned(16)));
static u64 local_alarm_object[2],local_alarm_vector[1];
static u64 local_send_listener[3];
static u8 local_alarm_records[4][16] __attribute__((aligned(16)));
static u32 local_alarm_count,local_alarm_interval;
static u64 local_alarm_handle;
static u8 aux[0xc000] __attribute__((aligned(16)));
/* Config arrays coexist with session objects and vector reallocations. Keep a
 * fixed 1 MiB process-local budget; no allocation changes vehicle state. */
static u8 arena[0x100000] __attribute__((aligned(16)));
static u8 input[1024], tls[128], vin[18];
static struct bounded_arena heap;
static u8 framed[1024], decoded[1024], body[1024];
static u8 native_packet_scratch[256] __attribute__((aligned(16)));
static u8 native_502_scratch[0x500] __attribute__((aligned(16)));
/* The selected original C++ functions read the AArch64 thread canary at
 * TPIDR_EL0+0x28.  This freestanding process has no libc-provided TCB. */
static u64 native_tcb[16] __attribute__((aligned(16)));
static char iccid[21],imsi[16],serial[92],domain[256],apn_type[32];
static u8 dns_addresses[4][4];
static u64 dns_address_list[5];
static u32 dns_count;
static u8 key[16],uuid[16],random_nonce[16];
static u32 timestamp,body_size,expected_command,prepared,callbacks;

static u32 sends, last_command, subscription_bytes, frames, frame_size, body_command;
static u32 pending_network[16];
static u64 pending_network_head,pending_network_tail;
static u32 decodes, decoded_size, decoded_command;
static struct pt_scheduler timer_queue;
static u32 current_id, session_epoch;
static u64 uptime_ms,wall_ms;
static u32 clock_ready;
static u32 local_tm[9];
struct unlock_event {u64 handle;u64 reserved;u64 lambda[2];};
static struct unlock_event *active_unlock_event;
static struct unlock_event *active_heartbeat_event;
static struct unlock_event *active_config_event;
static u8 *heartbeat_state;
static u32 timer_manager_registered;
static u32 config_handler_registered;
static u32 auxiliary_manager_registered;
static u32 sender_thread_registered;
static u8 *native_sender;
static u32 sender_slot_used;
static u8 *secondary_transport;
static u32 secondary_connected;
static u32 sender_callback_generation;
static u32 wake_condition_generation;
static u32 wake_mutex_held;
static char private_tcp_step;
static char private_tcp_connect_status='0';
#ifdef CONTROL_EXPERIMENT
/* Supplied fresh, read-only SDK scalars. Never defaults for missing live data. */
static u32 env[8], env_ready, env_valid, control_replies, control_writes, terminal;
static u32 wake_values[3],wake_valid,wake_ready;
#endif
static long syscall6(long n,long a,long b,long c,long d,long e,long f) {
 register long x0 __asm__("x0")=a,x1 __asm__("x1")=b,x2 __asm__("x2")=c;
 register long x3 __asm__("x3")=d,x4 __asm__("x4")=e,x5 __asm__("x5")=f;
 register long x8 __asm__("x8")=n;
 __asm__ volatile("svc #0":"+r"(x0):"r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5),"r"(x8):"memory","cc");return x0;
}
static u64 length(const char *s){u64 n=0;while(s[n])n++;return n;}
static int equal(const void *a,const void *b,u64 n){const u8 *x=a,*y=b;for(u64 i=0;i<n;i++)if(x[i]!=y[i])return 0;return 1;}
static void copy(void *d,const void *s,u64 n){u8 *x=d;const u8 *y=s;for(u64 i=0;i<n;i++)x[i]=y[i];}
static void fill(void *d,u8 b,u64 n){u8 *x=d;for(u64 i=0;i<n;i++)x[i]=b;}
static void text(const char *s){syscall6(64,1,(long)s,length(s),0,0,0);}
static void number(u32 n);
static void number64(u64 n);
static u32 write_decimal(char *out,u32 value){
 char reverse[12];u32 n=0,at=0;do{reverse[n++]='0'+value%10;value/=10;}while(value);
 while(n)out[at++]=reverse[--n];return at;
}
static u32 signed_decimal(char *out,u32 bits){
 u32 at=0,magnitude=bits;
 if(bits&0x80000000u){out[at++]='-';magnitude=~bits+1;}
 char reverse[11];u32 n=0;do{reverse[n++]='0'+magnitude%10;magnitude/=10;}while(magnitude);
 while(n)out[at++]=reverse[--n];out[at]=0;return at;
}
static void hex(const u8 *in,u32 n);
static int line(char *out,u32 cap);
static u64 decimal(const char *s);
static u32 signed_input(const char *s);
static u32 unhex(const char *s,u8 *out,u32 cap);
static int parse_native_integer(const char *input);
static char *token(char **cursor);
static void require(int ok,const char *why);
static void run_due_timers(void);
static void native_alarm_interval(void *self,u32 event,u32 seconds){
 require(self==local_alarm_object && event==3 && seconds==70 && clock_ready,
         "alarm_interval_boundary");
 local_alarm_interval=seconds;
}
static void native_alarm_start(void *self,u32 event){
 require(self==local_alarm_object && event==3 && local_alarm_interval==70 &&
         clock_ready,"alarm_start_boundary");
 if(local_alarm_handle){
  require(pt_delete(&timer_queue,local_alarm_handle)==0,"alarm_restart_cancel");
  text("CANCEL ");number(current_id);text(" ");number(session_epoch);
  text(" ");number64(local_alarm_handle);text("\n");
 }
 require(pt_create(&timer_queue,6,(u64)(base+0x539d8),(u64)object,
                   &local_alarm_handle)==0,"alarm_create");
 require(pt_arm(&timer_queue,local_alarm_handle,(u64)local_alarm_interval*1000,0)==0,
         "alarm_arm");
 text("ARM ");number(current_id);text(" ");number(session_epoch);
 text(" ");number64(local_alarm_handle);text(" ");
 number64(pt_find(&timer_queue,local_alarm_handle)->deadline_ms);text("\n");
}
/* The original 0x5aef4 stores (alarm-id, sp<record>) pairs in its
 * SortedVector. Only its exact object+0x370 instance is admitted here. */
static long native_alarm_index(void *self,const void *item){
 require(self==object+0x370 && item,"alarm_index_boundary");
 u32 event=*(const u32 *)item;
 for(u32 i=0;i<local_alarm_count;i++)
  if(*(u32 *)local_alarm_records[i]==event)return i;
 return -1;
}
static long native_alarm_add(void *self,const void *item){
 require(self==object+0x370 && item && local_alarm_count<4,
         "alarm_add_boundary");
 require(native_alarm_index(self,item)<0,"alarm_add_duplicate");
 u32 at=local_alarm_count++;
 copy(local_alarm_records[at],item,16);
 return at;
}
static void *native_alarm_edit(void *self,u64 index){
 require(self==object+0x370 && index<local_alarm_count,"alarm_edit_boundary");
 return local_alarm_records[index];
}
__attribute__((noreturn)) static void fail(const char *why){text("{\"passed\":false,\"stage\":\"");text(why);text("\"}\n");syscall6(94,1,0,0,0,0,0);for(;;){}}
static void require(int ok,const char *why){if(!ok)fail(why);}
static long nothing(void){return 0;}
static void *allocate(u64 n){
 enum arena_status status;void *p=arena_allocate(&heap,n,&status);
 require(status!=ARENA_EXHAUSTED,"allocation_exhausted");
 require(status==ARENA_OK,"allocation_bound");return p;
}
__attribute__((used)) static void *native_cpp_allocate_impl(u64 n,const void *caller){
 // Only the original sender constructor owns this slot. A JSON string or
 // an unrelated object can have the same size and must use the ordinary heap.
 if(caller==base+0x36ef8){
  require(n==sizeof(sender_slot)&&!sender_slot_used,"sender_singleton_overlap");
  sender_slot_used=1;fill(sender_slot,0,sizeof(sender_slot));
  native_sender=sender_slot+0x18;return sender_slot;
 }
 /* Both scalar and array operator new must return a distinct live allocation
  * even for zero bytes. The stock config continuation calls new[](140*count)
  * unconditionally, including count == 0. */
 return allocate(n?n:1);
}
__attribute__((naked)) static void native_cpp_allocate(void){
 __asm__ volatile("mov x1, x30\nb native_cpp_allocate_impl");
}
static void deallocate(void *p){
 if(p==sender_slot){require(sender_slot_used,"sender_singleton_release");
  sender_slot_used=0;native_sender=0;return;}
 require(arena_deallocate(&heap,p)==ARENA_OK,"deallocation_bound");}
/* Matching libutils VectorImplC2Emj ABI: the vendor constructor requests an
 * eight-byte element and zero flags for its secondary sender queue. The
 * virtual methods and worker still need original-code closure. */
static void *native_vector_ctor(void *self,u64 item_size,u32 flags){
 require(self==native_sender+0x10 && item_size==8 && flags==0,"vector_ctor_boundary");
 *(u64 *)((u8 *)self+8)=0;*(u64 *)((u8 *)self+16)=0;
 *(u32 *)((u8 *)self+24)=flags;*(u64 *)((u8 *)self+32)=item_size;
 return self;
}
static long native_vector_capacity(void *self,u64 requested){
 require(self==native_sender+0x10&&requested==20&&
         *(u64 *)((u8 *)self+16)==0&&*(u64 *)((u8 *)self+32)==8,
         "sender_capacity_boundary");
 u8 *buffer=allocate(24+20*8);fill(buffer,0,24+20*8);
 *(u64 *)(buffer+8)=20*8;
 *(u64 *)((u8 *)self+8)=(u64)(buffer+24);
 return 20;
}
static long native_vector_insert(void *self,const void *item,u64 position,u64 count){
 require(self==native_sender+0x10&&item&&count==1&&
         *(u64 *)((u8 *)self+32)==8,"sender_insert_shape");
 u64 size=*(u64 *)((u8 *)self+16);
 require(position==size&&size<20,"sender_queue_bound");
 u8 *data=(u8 *)(*(u64 *)((u8 *)self+8));require(data!=0,"sender_queue_storage");
 copy(data+size*8,item,8);*(u64 *)((u8 *)self+16)=size+1;
 return (long)position;
}
static void *native_vector_edit(void *self){
 require(self==native_sender+0x10,"sender_edit_shape");
 return (void *)(*(u64 *)((u8 *)self+8));
}
static long native_vector_remove(void *self,u64 position,u64 count){
 require(self==native_sender+0x10 && count==1,"sender_remove_shape");
 u64 size=*(u64 *)((u8 *)self+16);
 require(position<size,"sender_remove_index");
 u8 *data=(u8 *)(*(u64 *)((u8 *)self+8));
 require(data!=0,"sender_remove_storage");
 for(u64 i=position;i+1<size;i++)copy(data+i*8,data+(i+1)*8,8);
 fill(data+(size-1)*8,0,8);
 *(u64 *)((u8 *)self+16)=size-1;
 return (long)position; /* libutils VectorImpl::removeItemsAt ABI */
}
static int native_sender_signal(void *condition){
 require(sender_thread_registered,"sender_signal_shape");
 if(condition==native_sender+0x124){sender_callback_generation++;return 0;}
 require(condition==native_sender+0x60,"sender_signal_shape");
 require(*(u64 *)(native_sender+0x20)>0,"sender_signal_queue");
 if(native_sender[0x48])((void (*)(void *))(base+0x752d8))(native_sender);
 return 0;
}
static int native_sender_cond_init(void *self,const void *attributes){
 require(attributes==0&&native_sender&&
         (self==native_sender+0xe0||self==native_sender+0x90||
          self==native_sender+0xb8||self==native_sender+0x154),"sender_cond_init_boundary");
 fill(self,0,48);return 0;
}
static int native_sender_mutex_init(void *self,const void *attributes){
 require(attributes==0&&native_sender&&
         (self==native_sender+0x60||self==native_sender+0x124),
         "sender_mutex_init_boundary");
 fill(self,0,40);return 0;
}
static void *bounded_copy(void *d,const void *s,u64 n){require(n<=ARENA_MAX_REQUEST,"copy_bound");copy(d,s,n);return d;}
static void *bounded_move(void *d,const void *s,u64 n){
 require(n<=ARENA_MAX_REQUEST,"move_bound");u8 *dst=d;const u8 *src=s;
 if((u64)dst>(u64)src && (u64)dst-(u64)src<n){
  for(u64 i=n;i>0;i--)dst[i-1]=src[i-1];
 }else copy(dst,src,n);
 return d;
}
static char *bounded_strncpy(char *d,const char *s,u64 n){
 require(n<=ARENA_MAX_REQUEST,"strncpy_bound");u64 i=0;for(;i<n&&s[i];i++)d[i]=s[i];
 for(;i<n;i++)d[i]=0;return d;
}
static char *native_strncpy_chk2(char *d,const char *s,u64 n,u64 dest_size,u64 source_size){
 require(d&&s&&n<=ARENA_MAX_REQUEST&&n<=dest_size,"strncpy_checked_dest");
 u64 i=0;
 for(;i<n;i++){
  require(i<source_size,"strncpy_checked_source");
  d[i]=s[i];if(!s[i]){i++;break;}
 }
 for(;i<n;i++)d[i]=0;
 return d;
}
/* libc strsep ABI only; separators and field meaning remain original code. */
static char *native_strsep(char **cursor,const char *delimiters){
 require(cursor&&delimiters,"strsep_pointer");
 if(!*cursor)return 0;
 u64 dn=0;while(dn<64&&delimiters[dn])dn++;
 require(dn<64,"strsep_delimiter_bound");
 char *start=*cursor;
 for(u64 i=0;i<1024;i++){
  if(!start[i]){*cursor=0;return start;}
  for(u64 j=0;j<dn;j++)if(start[i]==delimiters[j]){
   start[i]=0;*cursor=start+i+1;return start;
  }
 }
 fail("strsep_string_bound");
}
static void *checked_copy(void *d,const void *s,u64 n,u64 bound){require(n<=bound,"checked_copy_bound");return bounded_copy(d,s,n);}
static void *bounded_set(void *d,int b,u64 n){require(n<=ARENA_MAX_REQUEST,"set_bound");fill(d,b,n);return d;}
__attribute__((noreturn)) static void event_publisher_unqualified(void){
 fail("event_publisher_unqualified");
}
static u64 bounded_length(const char *s){u64 n=0;while(n<128 && s[n])n++;require(n<128,"string_bound");return n;}
/* Every primitive external call is synchronous and bound to the active OP.
 * The parent may return only the precise typed reply; a failure is fatal. */
static const char *call_reply(const char *kind){
 static char answer[1200];require(current_id&&session_epoch,"call_without_operation");
 require(line(answer,sizeof(answer)),"call_reply_eof");
 char prefix[64];u32 at=0;const char *p="RET ";while(*p)prefix[at++]=*p++;
 at+=write_decimal(prefix+at,current_id);prefix[at++]=' ';
 at+=write_decimal(prefix+at,session_epoch);prefix[at++]=' ';
 p=kind;while(*p)prefix[at++]=*p++;
 prefix[at++]=' ';prefix[at]=0;
 require(equal(answer,prefix,at)&&answer[at],"call_reply_failed");
 return answer+at;
}
static void call_ok_reply(void){
 char answer[128],expected[128];u32 at=0;
 const char *p="RET ";while(*p)expected[at++]=*p++;
 at+=write_decimal(expected+at,current_id);expected[at++]=' ';
 at+=write_decimal(expected+at,session_epoch);
 p=" OK";while(*p)expected[at++]=*p++;
 expected[at]=0;
 require(line(answer,sizeof(answer))&&equal(answer,expected,at+1),"secondary_close_reply");
}
/* Generic original secondary TLS class boundary.  The firmware still builds
 * and queues its packet; only the class's socket/SSL implementation is
 * delegated to the owning Java mutual-TLS transport. */
static void *native_secondary_ctor(void *self,void *receiver,void *completion){
 require(self && native_sender && receiver==native_sender+0x38 &&
         completion==native_sender+0x180 &&
         !secondary_connected,"secondary_ctor_shape");
 fill(self,0,0x5c0);secondary_transport=self;
 *(u64 *)((u8 *)self+0x428)=(u64)receiver;
 *(u64 *)((u8 *)self+0x560)=(u64)completion;
 return self;
}
static int native_dns_address_matches(const char *ip){
 for(u32 i=0;i<dns_count;i++){
  char dotted[16];u32 at=0;
  for(u32 j=0;j<4;j++){
   if(j)dotted[at++]='.';
   at+=write_decimal(dotted+at,dns_addresses[i][j]);
  }
  dotted[at]=0;
  if(equal(dotted,ip,at+1))return 1;
 }
 return 0;
}
static int native_secondary_connect(void *self,const char *ip,u32 port,u32 mode){
 require(self==secondary_transport && ip && port>0 && port<=65535 &&
         mode<=1 && domain[0] && dns_count>0 &&
         native_dns_address_matches(ip) &&
         port==*(u16 *)(native_sender+0x5c),"secondary_endpoint_provenance");
 u32 host_n=(u32)bounded_length(domain),ip_n=(u32)bounded_length(ip);
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" SECONDARY_CONNECT ");hex((const u8 *)domain,host_n);
 text(" ");hex((const u8 *)ip,ip_n);text(" ");number(port);text("\n");
 const char *reply=call_reply("CONNECTED");
 require(equal(reply,"0",2)||equal(reply,"1",2),"secondary_connect_reply");
 secondary_connected=(u32)(reply[0]-'0');
 *(u32 *)((u8 *)self+0x558)=secondary_connected?3:0;
 *(u32 *)((u8 *)self+0x20)=secondary_connected?1:0; /* logical handle */
 *(u8 *)((u8 *)self+0x569)=(u8)mode;
 return (int)secondary_connected;
}
static int native_secondary_write(void *self,const u8 *bytes,u32 size){
 require(self==secondary_transport && secondary_connected && bytes &&
         size>0 && size<=0x400,"secondary_write_shape");
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" SECONDARY_WRITE ");hex(bytes,size);text("\n");
 const char *reply=call_reply("WRITTEN");
 require(decimal(reply)==size,"secondary_partial_write");
 /* This is the same original completion that the TLS send worker invokes
  * after a complete SSL_write, now after the actual Java TLS result. */
 u64 *completion_slot=(u64 *)(*(u64 *)((u8 *)self+0x560));
 require(completion_slot==(u64 *)(native_sender+0x180) && *completion_slot &&
         *(u64 *)(*completion_slot+0x10)==(u64)native_sender,
         "secondary_completion_object");
 ((void (*)(void *,int))(base+0x73228))((void *)*completion_slot,1);
 return 1;
}
static void native_secondary_close(void *self){
 require(self==secondary_transport,"secondary_close_shape");
 if(secondary_connected){
  text("CALL ");number(current_id);text(" ");number(session_epoch);
  text(" SECONDARY_CLOSE\n");
  call_ok_reply();
 }
 secondary_connected=0;*(u32 *)((u8 *)self+0x558)=0;
 *(u32 *)((u8 *)self+0x20)=0;
}
static u32 call_integer(u32 device,u32 fid,int floating){
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(floating?" GET_FLOAT ":" GET_INT ");number(device);text(" ");number(fid);text("\n");
 const char *value=call_reply("VALUE");u64 n=floating?decimal(value):(u64)signed_input(value);
 require(n<=0xffffffffu,"getter_reply_bound");return (u32)n;
}
static u32 call_property_get(const char *name,char *out,u32 cap){
 require(name&&out&&cap>0&&cap<=128,"property_get_shape");
 text("CALL ");number(current_id);text(" ");number(session_epoch);text(" PROPERTY_GET ");
 hex((const u8 *)name,(u32)bounded_length(name));text("\n");
 const char *value=call_reply("VALUE");
 if(equal(value,"-",2)){out[0]=0;return 0;}
 u32 n=unhex(value,(u8 *)out,cap-1);require(n>0,"property_get_empty_encoding");
 out[n]=0;for(u32 i=0;i<n;i++)require((u8)out[i]>=32&&(u8)out[i]<=126,"property_get_ascii");
 return n;
}
struct native_file {u32 fd;};
static struct native_file readonly_files[2];
static struct native_file *native_file_handle(void *file){
 require((file==&readonly_files[0]||file==&readonly_files[1])&&
         ((struct native_file *)file)->fd,"file_handle_boundary");
 return file;
}
static void file_call(const char *verb,u32 fd){
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" ");text(verb);text(" ");number(fd);
}
static void *native_fopen(const char *path,const char *mode){
 require(path&&mode&&length(path)<=128&&length(mode)<=3,"file_open_shape");
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" FILE_OPEN ");hex((const u8 *)path,(u32)length(path));text(" ");
 hex((const u8 *)mode,(u32)length(mode));text("\n");
 int fd=(int)signed_input(call_reply("VALUE"));
 if(fd<0)return 0;
 require(fd>0,"file_open_zero");
 for(u32 i=0;i<2;i++)if(!readonly_files[i].fd){readonly_files[i].fd=(u32)fd;return &readonly_files[i];}
 fail("file_handles_full");
}
static u64 native_fread(void *out,u64 size,u64 count,void *handle){
 struct native_file *file=native_file_handle(handle);
 if(!size||!count)return 0;
 require(out&&size<=65536&&count<=65536/size,"file_read_bound");
 u32 total=(u32)(size*count),done=0;
 while(done<total){
  u32 chunk=total-done;if(chunk>512)chunk=512;
  file_call("FILE_READ",file->fd);text(" ");number(chunk);text("\n");
  char *cursor=(char *)call_reply("BUFFER");int status=(int)signed_input(token(&cursor));
  char *encoded=token(&cursor);require(!*cursor,"file_read_reply");
  if(status<0){require(equal(encoded,"-",2),"file_error_data");fail("file_read_failed");}
  require(status==0,"file_read_status");
  u32 n=equal(encoded,"-",2)?0:unhex(encoded,(u8 *)out+done,chunk);
  done+=n;if(n<chunk)break;
 }
 return done/size;
}
static int native_feof(void *handle){
 struct native_file *file=native_file_handle(handle);
 file_call("FILE_EOF",file->fd);text("\n");
 int result=(int)signed_input(call_reply("VALUE"));require(result==0||result==1,"file_eof_status");return result;
}
static int native_fclose(void *handle){
 struct native_file *file=native_file_handle(handle);
 file_call("FILE_CLOSE",file->fd);text("\n");
 int result=(int)signed_input(call_reply("VALUE"));file->fd=0;return result;
}
static int native_file_access(const char *path,int mode){
 require(path&&length(path)<=128&&mode>=0&&mode<=7,"file_access_shape");
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" FILE_ACCESS ");hex((const u8 *)path,(u32)length(path));text(" ");number((u32)mode);text("\n");
 return (int)signed_input(call_reply("VALUE"));
}
static int native_file_remove(const char *path){
 require(path&&length(path)<=128,"file_remove_shape");
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" FILE_REMOVE ");hex((const u8 *)path,(u32)length(path));text("\n");
 return (int)signed_input(call_reply("VALUE"));
}
/* The host owns the per-session file namespace.  The original routines see
 * ordinary libc return values, including the policy's negative refusal for
 * writes to the stock cache or diagnostic marker. */
static int native_file_chmod(const char *path,u32 mode){
 require(path&&length(path)<=128&&mode<=07777,"file_chmod_shape");
 return -1;
}
static int native_file_seek(void *handle,long offset,int whence){
 (void)native_file_handle(handle);
 require(whence>=0&&whence<=2&&offset>=-65536&&offset<=65536,"file_seek_shape");
 return -1;
}
static u64 native_fwrite(const void *bytes,u64 size,u64 count,void *handle){
 struct native_file *file=native_file_handle(handle);
 if(!size||!count)return 0;
 require(bytes&&size<=65536&&count<=65536/size,"file_write_bound");
 u32 total=(u32)(size*count),done=0;
 while(done<total){
  u32 chunk=total-done;if(chunk>512)chunk=512;
  file_call("FILE_WRITE",file->fd);text(" ");hex((const u8 *)bytes+done,chunk);text("\n");
  int actual=(int)signed_input(call_reply("VALUE"));
  if(actual<0)break;
  require((u32)actual<=chunk,"file_write_reply_bound");
  done+=(u32)actual;if((u32)actual<chunk)break;
 }
 return done/size;
}
static u64 native_fwrite_checked(const void *bytes,u64 size,u64 count,void *handle,u64 object_size){
 require(!size||count<=object_size/size,"file_write_object_bound");
 return native_fwrite(bytes,size,count,handle);
}
/* Original 0x52ea0 consumes this imported Android DNS hostent. The external
 * resolver supplies ordered raw IPv4 addresses; C only exposes its ABI. */
static int native_dns(const char *host,u8 *hostent){
 require(host&&hostent&&current_id&&session_epoch,"dns_shape");
 u32 n=(u32)bounded_length(host);
 /* The original readiness callback can ask the resolver for its still-empty
  * discovery hostname.  An empty DNS name has no A record: expose a failed
  * hostent to the original caller rather than inventing an endpoint. */
 if(!n){fill(hostent,0,32);dns_count=0;return -1;}
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" DNS_LOOKUP ");hex((const u8 *)host,n);text("\n");
 char *encoded=(char *)call_reply("IPV4");
 fill(hostent,0,32);fill(dns_address_list,0,sizeof(dns_address_list));
 if(equal(encoded,"-",2)){dns_count=0;return 0;}
 u32 count=0;char *cursor=encoded;
 while(*cursor){
  require(count<4,"dns_addresses_bound");
  char *address=token(&cursor);
  require(unhex(address,dns_addresses[count],4)==4,"dns_address_shape");
  u32 i=count++;
  dns_address_list[i]=(u64)dns_addresses[i];
 }
 require(count>0,"dns_addresses_empty");
 dns_count=count;
 *(u32 *)(hostent+16)=2;*(u32 *)(hostent+20)=4;
 *(u64 *)(hostent+24)=(u64)dns_address_list;
 copy(domain,host,n+1);
 return 0;
}
static const char *native_inet_ntop(u32 family,const u8 *address,char *out,u32 cap){
 require(address&&out&&cap==16&&family==2,"inet_ntop_shape");
 u32 at=0;
 for(u32 i=0;i<4;i++){
  if(i)out[at++]='.';
  at+=write_decimal(out+at,address[i]);
 }
 require(at<cap,"inet_ntop_bound");out[at]=0;return out;
}
/* BYDAutoManager::getBuffer copies ownership to its caller, which later calls
 * free(3). Preserve the native status and byte ownership; the host only reads
 * the SDK buffer and the firmware still decides how to use its contents. */
static int native_get_buffer(void *sdk,u32 device,u32 fid,u8 **out,u32 *size){
 (void)sdk;require(out&&size&&device==1027&&
     (fid==0x99000002||fid==0x9900021a||fid==0x99000035||fid==0x99000402),"get_buffer_boundary");
 *out=0;*size=0;
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" GET_BUFFER ");number(device);text(" ");number(fid);text("\n");
 char *reply=(char *)call_reply("BUFFER"),*cursor=reply;
 u32 status=signed_input(token(&cursor));char *encoded=token(&cursor);
 require(!*cursor,"get_buffer_reply_shape");
 if(status){require(equal(encoded,"-",2),"get_buffer_error_shape");return (int)status;}
 u8 bytes[512];u32 n=equal(encoded,"-",2)?0:unhex(encoded,bytes,sizeof(bytes));
 u8 *owned=allocate(n?n:1);if(n)copy(owned,bytes,n);
 *out=owned;*size=n;return 0;
}
static void *native_localtime(const long *seconds){
 require(seconds && current_id && session_epoch,"localtime_input");
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" LOCALTIME ");
 if(*seconds<0){text("-");number64((u64)(-(*seconds+1))+1);}
 else number64((u64)*seconds);
 text("\n");
 char *value=(char *)call_reply("TM"),*cursor=value;
 for(u32 i=0;i<9;i++)local_tm[i]=signed_input(token(&cursor));
 require(!*cursor&&local_tm[0]<=60&&local_tm[1]<=59&&local_tm[2]<=23&&
         local_tm[3]>=1&&local_tm[3]<=31&&local_tm[4]<=11&&local_tm[5]<=900&&
         local_tm[6]<=6&&local_tm[7]<=365&&local_tm[8]<=1,"localtime_reply_bound");
 return local_tm;
}
static long native_mktime(u32 *tm){
 require(tm==local_tm && current_id && session_epoch,"mktime_input");
 text("CALL ");number(current_id);text(" ");number(session_epoch);text(" MKTIME");
 for(u32 i=0;i<9;i++){char digits[16];signed_decimal(digits,tm[i]);text(" ");text(digits);}
 text("\n");
 const char *value=call_reply("VALUE");int negative=*value=='-';
 u64 magnitude=decimal(negative?value+1:value);
 require(magnitude<=(negative?0x8000000000000000ul:0x7ffffffffffffffful),"mktime_reply_bound");
 return negative?-(long)(magnitude-1)-1:(long)magnitude;
}
/* Standard libm primitive. JSON syntax and configuration decisions remain
 * in the original parser; the host supplies only IEEE754 pow(). */
static double native_pow(double left,double right){
 union {double value;u64 bits;} a={.value=left},b={.value=right},result;
 require(current_id&&session_epoch,"math_operation_missing");
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" MATH_POW ");number64(a.bits);text(" ");number64(b.bits);text("\n");
 result.bits=decimal(call_reply("VALUE"));return result.value;
}
static double native_atof(const char *input){
 require(input&&current_id&&session_epoch,"atof_operation_missing");
 u64 n=bounded_length(input);union {double value;u64 bits;} result;
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" C_ATOF ");if(n)hex((const u8 *)input,(u32)n);else text("-");text("\n");
 result.bits=decimal(call_reply("VALUE"));return result.value;
}
static void load_environment(const char *value){
 u8 snapshot[36];require(unhex(value,snapshot,sizeof(snapshot))==sizeof(snapshot),"environment_input");
 copy(env,snapshot,32);copy(&env_valid,snapshot+32,4);
 require((env_valid&0xc3)==0xc3,"state_getter_failed");
 *(u32 *)(object+0x28c)=env[0];*(u32 *)(object+0x290)=env[6];env_ready=1;
}
static void load_wake_values(const char *value){
 u8 snapshot[16];require(unhex(value,snapshot,sizeof(snapshot))==sizeof(snapshot),"state_environment_input");
 copy(wake_values,snapshot,12);copy(&wake_valid,snapshot+12,4);wake_ready=1;
}
/* These are the three libc boundaries used by original 0x69868/0x69974.
 * The callback and native timer body themselves remain firmware instructions. */
static int native_timer_create(int clock_id,const u8 *event,u64 *out){
 require(clock_id==0 && event && out && *(const u32 *)(event+12)==2,"timer_create_boundary");
 unsigned index=out==(u64 *)(object+0x5d8)?0:out==(u64 *)(object+0x5c8)?1:
                out==(u64 *)(object+0x5b8)?2:out==(u64 *)(object+0x5c0)?3:
                out==(u64 *)(object+0x5d0)?8:9;
 require(index<PT_TIMER_SLOTS,"timer_slot_unknown");
 u64 callback=*(const u64 *)(event+16),context=*(const u64 *)event;
 require((index==0 && callback==(u64)(base+0x5eef0)) ||
         (index==1 && callback==(u64)(base+0x4a480)) ||
         (index==2&&callback==(u64)(base+0x5e7e8)) ||
         (index==3&&(callback==(u64)(base+0x5e7e8)||
                     callback==(u64)(base+0x56b90))) ||
         (index==8 && callback==(u64)(base+0x5f154)),"timer_callback_boundary");
 require(context==(u64)object,"timer_context_boundary");
 require(pt_create(&timer_queue,index,callback,context,out)==0,"timer_create_state");
 return 0;
}
static int native_timer_settime(u64 handle,int flags,const u64 *spec,void *old){
 require(flags==0 && spec && !old && !spec[1] && !spec[3],"timer_settime_boundary");
 require(spec[2]>=1&&spec[2]<=((handle&255)==9?65535:255),"timer_duration_boundary");
 struct pt_timer *t=pt_find(&timer_queue,handle);
 require(t && ((t==&timer_queue.slot[0] && (spec[2]==16 || spec[2]==32)) ||
               (t==&timer_queue.slot[1] && (spec[2]==10 || spec[2]==5)) ||
               (t==&timer_queue.slot[2]&&(spec[2]==2 || spec[2]==3)) ||
               (t==&timer_queue.slot[3]&&spec[2]>=1&&spec[2]<=255) ||
               (t==&timer_queue.slot[8]&&spec[2]>=1&&spec[2]<=65535)),"timer_slot_boundary");
 require(pt_arm(&timer_queue,handle,spec[2]*1000,spec[0]*1000)==0,"timer_arm");
 text("ARM ");number(current_id);text(" ");number(session_epoch);text(" ");number64(handle);
 text(" ");number64(t->deadline_ms);text("\n");
 return 0;
}
static int native_timer_delete(u64 handle){
 require(pt_delete(&timer_queue,handle)==0,"timer_delete_boundary");
 text("CANCEL ");number(current_id);text(" ");number(session_epoch);text(" ");number64(handle);text("\n");
 return 0;
}
static int timer_guard_acquire(u8 *guard){
 require(guard==(u8 *)*(u64 *)(base+0x8ea18)||
         guard==(u8 *)*(u64 *)(base+0x8ea40)||
         guard==(u8 *)*(u64 *)(base+0x8ed38)||
         guard==(u8 *)*(u64 *)(base+0x8ed48)||
         guard==(u8 *)*(u64 *)(base+0x8ec38)||
         guard==(u8 *)*(u64 *)(base+0x8ed58)||
         guard==(u8 *)*(u64 *)(base+0x8ee00),"timer_guard_boundary");
 if(*guard==1)return 0;
 require(*guard==0,"timer_guard_recursive");*guard=2;return 1;
}
static void timer_guard_release(u8 *guard){
 require((guard==(u8 *)*(u64 *)(base+0x8ea18)||
          guard==(u8 *)*(u64 *)(base+0x8ea40)||
          guard==(u8 *)*(u64 *)(base+0x8ed38)||
          guard==(u8 *)*(u64 *)(base+0x8ed48)||
          guard==(u8 *)*(u64 *)(base+0x8ec38)||
          guard==(u8 *)*(u64 *)(base+0x8ed58)||
          guard==(u8 *)*(u64 *)(base+0x8ee00))&&*guard==2,
         "timer_guard_release");*guard=1;
}
static void *timer_manager_construct(void *manager){
 require(manager==(void *)*(u64 *)(base+0x8ea48)||
         manager==(void *)*(u64 *)(base+0x8ed40),"timer_manager_boundary");
 return manager;
}
static int timer_manager_atexit(void *destructor,void *manager,void *dso){
 (void)destructor;(void)dso;
 /* These singleton constructors own only this process's bounded arena.
  * Keep their real guard/object pairs; process exit releases the arena.
  * No timer, file, or SDK registration is owned by these destructors here. */
 const u64 data_objects[][3]={{0x8ec38,0x8ec40,0x442a0},
                            {0x8ed58,0x8ed60,0x4104c},
                            {0x8ee00,0x8ee08,0x46684}};
 for(u32 i=0;i<3;i++)if(manager==(void *)*(u64 *)(base+data_objects[i][1])){
  require(destructor==base+data_objects[i][2]&&
          **(u8 **)(base+data_objects[i][0])==2,"data_singleton_atexit");
  return 0;
 }
 if(manager==(void *)*(u64 *)(base+0x8ed50)){
  require(destructor==base+0x390e4&&!config_handler_registered&&active_config_event,
          "config_handler_atexit");
  config_handler_registered=1;return 0;
 }
 if(manager==(void *)*(u64 *)(base+0x8ea20)){
  require(native_sender&&sender_thread_registered,"sender_singleton_atexit");
  return 0;
 }
 if(manager==(void *)*(u64 *)(base+0x8ed40)){
  require(!auxiliary_manager_registered,"auxiliary_manager_atexit");
  auxiliary_manager_registered=1;return 0;
 }
 require(manager==(void *)*(u64 *)(base+0x8ea48)&&!timer_manager_registered,
         "timer_manager_atexit");
 /* All manager resources are process-local scheduler state. At process exit,
  * no timer or callback survives the process lease. */
 timer_manager_registered=1;return 0;
}
__attribute__((used)) static void timer_manager_create_impl(void *manager,u64 delay,
 u64 repeat,u64 initial,const u64 *lambda,u64 unused,u64 type,u64 *out){
 (void)unused;
 require(manager==(void *)*(u64 *)(base+0x8ea48)&&timer_manager_registered&&
         delay==5000&&!initial&&type==7&&out&&lambda&&
         ((repeat==0&&lambda[0]==(u64)(base+0x8b330)&&lambda[1]==(u64)secondary)||
          (repeat==5000&&lambda[0]==(u64)(base+0x8aad0)&&lambda[1]==(u64)heartbeat_state)||
          (repeat==5000&&lambda[0]==(u64)(base+0x8abe0)&&lambda[1]==*(u64 *)(base+0x8ed50))),
         "timer_manager_create_boundary");
 unsigned slot=lambda[0]==(u64)(base+0x8abe0)?7:repeat?5:4;
 require(!(slot==7?active_config_event:slot==5?active_heartbeat_event:active_unlock_event),"timer_manager_overlap");
 struct unlock_event *event=allocate(sizeof(*event));
 u64 *counter=allocate(32);fill(event,0,sizeof(*event));fill(counter,0,32);
 copy(event->lambda,lambda,16);counter[1]=2;
 require(pt_create(&timer_queue,slot,(u64)(base+(slot==7?0x398a0:slot==5?0x38cbc:0x45ca4)),
                   (u64)event->lambda,&event->handle)==0,"native_timer_create");
 out[0]=(u64)event;out[1]=(u64)counter;
 if(slot==7)active_config_event=event;
 else if(slot==5)active_heartbeat_event=event;else active_unlock_event=event;
}
__attribute__((naked)) static void timer_manager_create(void){
 __asm__ volatile("mov x7, x8\nb timer_manager_create_impl");
}
static void timer_event_start(struct unlock_event *event){
 require(event && (event==active_unlock_event||event==active_heartbeat_event||event==active_config_event)&&event->handle,
         "native_timer_start_boundary");
 require(pt_arm(&timer_queue,event->handle,5000,
                event==active_unlock_event?0:5000)==0,"native_timer_arm");
 text("ARM ");number(current_id);text(" ");number(session_epoch);text(" ");
 number64(event->handle);text(" ");number64(pt_find(&timer_queue,event->handle)->deadline_ms);text("\n");
}
static void timer_event_stop(struct unlock_event *event){
 require(event && (event==active_unlock_event||event==active_heartbeat_event||event==active_config_event),"native_timer_stop_boundary");
 if(!event->handle)return;
 u64 handle=event->handle;
 if(event==active_unlock_event){require(pt_delete(&timer_queue,handle)==0,"unlock_timer_cancel");event->handle=0;active_unlock_event=0;}
 else {struct pt_timer *t=pt_find(&timer_queue,handle);require(t!=0,"heartbeat_timer_stop");t->active=0;}
 text("CANCEL ");number(current_id);text(" ");number(session_epoch);text(" ");
 number64(handle);text("\n");
}
static void timer_event_pause(struct unlock_event *event){
 require(event&&event==active_heartbeat_event&&event->handle,"heartbeat_timer_pause");
 struct pt_timer *t=pt_find(&timer_queue,event->handle);require(t!=0,"heartbeat_timer_pause_handle");
 t->active=0;
}
static void timer_event_restart(struct unlock_event *event){
 require(event&&event==active_heartbeat_event&&event->handle,"heartbeat_timer_restart");
 require(pt_arm(&timer_queue,event->handle,5000,5000)==0,"heartbeat_timer_rearm");
 text("ARM ");number(current_id);text(" ");number(session_epoch);text(" ");
 number64(event->handle);text(" ");number64(pt_find(&timer_queue,event->handle)->deadline_ms);text("\n");
}
/* Firmware 0x5eef0 creates a detached thread whose entry is 0x61230.
 * All IPC is serialized; run that exact entry before returning to the event
 * loop so no second input can race the native timeout state transition. */
static int native_thread_attr(void *arg){require(arg!=0,"thread_attr_boundary");return 0;}
static int native_thread_detach(void *arg,int state){require(arg && state==1,"thread_detach_boundary");return 0;}
static int native_thread_create(u64 *thread,void *attr,void *entry,void *arg){
 if(entry==base+0x7396c){
  require(thread&&attr&&arg==native_sender&&!sender_thread_registered,
          "sender_thread_boundary");
  sender_thread_registered=1;
  *thread=2;return 0;
 }
 require(thread && attr && (entry==base+0x61208||entry==base+0x61230||entry==base+0x611b8||
                            entry==base+0x611e0) && !arg,"thread_entry_boundary");
 *thread=1;((void *(*)(void *))entry)(arg);return 0;
}
static int native_usleep(u32 microseconds){
 require(microseconds>0&&microseconds<=1000000,"native_usleep_boundary");
 u64 interval[2]={microseconds/1000000,(u64)(microseconds%1000000)*1000};
 require(syscall6(101,(long)interval,0,0,0,0,0)==0,"native_usleep_failed");return 0;
}
static void native_notify_all(void *condition){
 require(condition==object+0x418,"wake_condition_boundary");
 wake_condition_generation++;
 text("NOTIFY ");number(current_id);text(" ");number(session_epoch);text(" ");number(wake_condition_generation);text("\n");
}
static int native_set_int(void *sdk,u32 device,u32 fid,u32 value){
 (void)sdk;require((device==1005&&fid==0xaa00004a&&value==1)||
                    (device==1027&&fid==0xaa000026&&value==0),"set_int_boundary");
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" SET_INT ");number(device);text(" ");number(fid);text(" ");number(value);text("\n");
 char reply[80];require(line(reply,sizeof(reply)),"wake_set_eof");
 char prefix[64];u32 at=0;const char *p="RET ";while(*p)prefix[at++]=*p++;
 at+=write_decimal(prefix+at,current_id);prefix[at++]=' ';
 at+=write_decimal(prefix+at,session_epoch);p=" OK";while(*p)prefix[at++]=*p++;
 prefix[at]=0;require(equal(reply,prefix,at+1),"wake_set_failed");return 0;
}
static u64 native_steady_clock(void){
 require(clock_ready && uptime_ms<=~(u64)0/1000000,"clock_ns_overflow");
 return uptime_ms*1000000;
}
static void advance_clocks(u64 elapsed,u64 uptime,u64 wall){
 require(uptime<=elapsed && wall<=~(u64)0/1000 &&
         (!clock_ready || (uptime>=uptime_ms && elapsed>=timer_queue.now_ms)),
         "clock_domain_boundary");
 require(pt_advance(&timer_queue,elapsed)==0,"clock_reversed");
 uptime_ms=uptime;wall_ms=wall;clock_ready=1;
}
static int native_mutex_lock(void *mutex){
 require(mutex==object+0x3c0&&!wake_mutex_held,"wake_lock_boundary");wake_mutex_held=1;return 0;
}
static int native_mutex_unlock(void *mutex){
 require(mutex==object+0x3c0&&wake_mutex_held,"wake_unlock_boundary");wake_mutex_held=0;return 0;
}
static int native_condition_wait(void *condition,void *mutex,u32 clock_id,const u64 *deadline){
 require(condition==object+0x418&&mutex==object+0x3c0&&clock_id==1&&
         deadline&&wake_mutex_held&&deadline[1]<1000000000,"wake_wait_boundary");
 require(deadline[0]<=(~(u64)0-deadline[1])/1000000000,"wake_deadline_overflow");
 u64 target_ns=deadline[0]*1000000000+deadline[1],now_ns=native_steady_clock();
 require(target_ns>=now_ns&&target_ns-now_ns<=1000000000,"wake_deadline_bound");
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" WAIT ");number64(target_ns);text("\n");
 char reply[256];require(line(reply,sizeof(reply)),"wake_wait_eof");
 char *cursor=reply;require(equal(token(&cursor),"RET",4),"wake_wait_tag");
 require(decimal(token(&cursor))==current_id&&decimal(token(&cursor))==session_epoch,"wake_wait_identity");
 char *kind=token(&cursor);
 if(equal(kind,"TIMEOUT",8)){
  u64 ms=decimal(token(&cursor)),elapsed=decimal(token(&cursor)),wall=decimal(token(&cursor));
  require(!*cursor&&ms<=~(u64)0/1000000&&ms*1000000>=target_ns,
          "wake_wait_timeout_clock");
  advance_clocks(elapsed,ms,wall);run_due_timers();
  return 110;
 }
 require(equal(kind,"EVENT",6),"wake_wait_result");
 u64 device=decimal(token(&cursor)),fid=decimal(token(&cursor));
 u32 value=signed_input(token(&cursor));u64 ms=decimal(token(&cursor));
 u64 elapsed=decimal(token(&cursor)),wall=decimal(token(&cursor));
 char *environment=token(&cursor),*wake=token(&cursor);
 require(!*cursor&&device==1005&&fid==0x99000003&&value<=1&&
         ms<=~(u64)0/1000000&&ms*1000000<=target_ns,"wake_wait_event_boundary");
 advance_clocks(elapsed,ms,wall);
 load_environment(environment);load_wake_values(wake);run_due_timers();
 frames=0;frame_size=0;
 ((void (*)(void *,u32,u32,u32))(base+0x6822c))(observer,(u32)device,(u32)fid,value);
 return 0;
}
static void run_due_timers(void){
 for(unsigned i=0;i<PT_TIMER_SLOTS;i++){
  u64 handle=0,callback=0,context=0;
  if(pt_due(&timer_queue,i,&handle,&callback,&context)){
   int standard_callback=(callback==(u64)(base+(i==0?0x5eef0:i==1?0x4a480:
                                              i==2?0x5e7e8:0x56b90)) ||
                          (i==3&&callback==(u64)(base+0x5e7e8))) &&
                         context==(u64)object;
   require(i==8 ? (callback==(u64)(base+0x5f154) && context==(u64)object) :
          i==7 ? (callback==(u64)(base+0x398a0) && active_config_event &&
                   context==(u64)active_config_event->lambda) :
          i==6 ? (callback==(u64)(base+0x539d8) &&
                   context==(u64)object && handle==local_alarm_handle) :
          i==5 ? (callback==(u64)(base+0x38cbc) && active_heartbeat_event &&
                   context==(u64)active_heartbeat_event->lambda) :
          i==4 ? (callback==(u64)(base+0x45ca4) && active_unlock_event &&
                   context==(u64)active_unlock_event->lambda) :
                  standard_callback,"timer_fire_boundary");
   if(i==6){
    local_alarm_handle=0;local_alarm_interval=0;
    ((void (*)(void *,u32))callback)((void *)context,3);
   }else ((void (*)(void *))callback)((void *)context);
   text("FIRED ");number(current_id);text(" ");number(session_epoch);text(" ");number64(handle);text("\n");
  }
 }
}
static int bounded_compare(const u8 *a,const u8 *b,u64 n){require(n<=ARENA_MAX_REQUEST,"compare_bound");for(u64 i=0;i<n;i++){if(a[i]!=b[i])return (int)a[i]-b[i];if(!a[i])break;}return 0;}
static int native_strcmp(const u8 *a,const u8 *b){
 require(a&&b,"strcmp_pointer");
 for(u32 i=0;i<4096;i++){int delta=(int)a[i]-(int)b[i];if(delta||!a[i])return delta;}
 fail("strcmp_bound");return 0;
}
static int property(const char *name,char *out,const char *fallback){
 const char *value=0;
 if(equal(name,"persist.sys.byd.apn_type",24))value=apn_type[0]?apn_type:0;
 else if(equal(name,"sys.tcp_step",13)){
  require(private_tcp_step,"private_tcp_step_missing");out[0]=private_tcp_step;out[1]=0;return 1;}
 else if(equal(name,"sys.tcp_connect_status",23)){
  out[0]=private_tcp_connect_status;out[1]=0;return 1;}
 else if(equal(name,"ril.csim.iccid",14))value=iccid;
 else if(equal(name,"ril.imsi",9))value=imsi;
 else if(equal(name,"debug.ro.serialno",18))value=serial;
 if(!value){u32 n=call_property_get(name,out,92);
  if(!n && fallback){n=(u32)bounded_length(fallback);copy(out,fallback,n+1);}
  return (int)n;}
 u64 n=bounded_length(value);copy(out,value,n+1);return n;
}
static long current_time(void){return (long)(clock_ready?wall_ms/1000:timestamp);}
/* std::__ndk1::chrono::system_clock::now() returns epoch nanoseconds here;
 * the original 0x38680/0x38a24 convert them to milliseconds. */
static long native_system_clock_now(void){
 require(clock_ready&&wall_ms<=9223372036854UL,"system_clock_domain");
 return (long)(wall_ms*1000000UL);
}
/* The sole original sysinfo call is the real-VIN branch at 0x4a30c.  It reads
 * only the first field (kernel uptime seconds) before recording the result in
 * sys.vin_valid_record_time. Android elapsedRealtime is CLOCK_BOOTTIME, which
 * includes suspend; uptimeMillis would lose the car's sleep time. */
static int native_sysinfo(void *out){
 require((u64)__builtin_return_address(0)==(u64)(base+0x4a310)&&
         out&&clock_ready&&current_id&&session_epoch&&
         timer_queue.now_ms/1000<=0x7fffffffUL,"vin_sysinfo_boundary");
 *(u64 *)out=timer_queue.now_ms/1000;return 0;
}
/* 0x699a4 is called by original producers, including internal continuations.
 * Ask the owner for fresh entropy at every call; a bootstrap N snapshot cannot
 * safely stand in for later calls made by original firmware code. */
static int nonce(u8 *out,u32 n){
 require(out&&n==16&&current_id&&session_epoch,"nonce_bound");
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" RANDOM_BYTES 16\n");
 const char *encoded=call_reply("BYTES");
 require(unhex(encoded,out,16)==16,"nonce_reply_bound");
 return 0;
}
static u64 native_string_length(const char *value);
static void *string_copy(char *out,const char *in){u64 n=native_string_length(in);copy(out,in,n+1);return out;}
/* The pinned NDK libc++ string ABI is a 24-byte object: short length*2
 * and 22 inline bytes, or allocation-count|1, length and owned data. These
 * are generic string operations, independent of any command or SDK value. */
#define NATIVE_STRING_MAX 4095U
static const u8 *native_string_data(const void *source,u64 *size){
 require(source&&size,"native_string_pointer");const u8 *s=source;
 if(!(s[0]&1)){
  *size=s[0]/2;require(*size<=22,"native_string_short_bound");return s+1;
 }
 u64 capacity=*(const u64 *)s&~1UL;*size=*(const u64 *)(s+8);
 const u8 *data=*(const u8 *const *)(s+16);
 require(data&&*size<capacity&&capacity<=4096,"native_string_long_bound");
 return data;
}
static u64 native_string_length(const char *value){
 require(value!=0,"native_string_source");
 u64 n=0;while(n<=NATIVE_STRING_MAX&&value[n])n++;
 require(n<=NATIVE_STRING_MAX,"native_string_length_bound");return n;
}
static u64 native_strlen_chk(const char *value,u64 object_size){
 require(value!=0,"native_string_source");
 u64 limit=object_size<NATIVE_STRING_MAX+1UL?object_size:NATIVE_STRING_MAX+1UL;
 for(u64 n=0;n<limit;n++)if(!value[n])return n;
 fail("native_strlen_checked_bound");
}
static void *native_string_construct(void *out,const void *bytes,u64 size){
 require(out&&bytes&&size<=NATIVE_STRING_MAX,"native_string_construct_bound");
 u8 image[24];fill(image,0,sizeof(image));
 if(size<=22){image[0]=(u8)(size*2);copy(image+1,bytes,size);}
 else{
  u64 capacity=(size+16)&~15UL;u8 *data=allocate(capacity);
  copy(data,bytes,size);data[size]=0;
  *(u64 *)image=capacity|1UL;*(u64 *)(image+8)=size;*(u64 *)(image+16)=(u64)data;
 }
 copy(out,image,24);return out;
}
static void *native_string_assign_n(void *out,const void *bytes,u64 size){
 u64 old_size;const u8 *old=native_string_data(out,&old_size);
 int owned=*(u8 *)out&1;
 /* Construct before releasing: assigning an overlapping substring is valid. */
 native_string_construct(out,bytes,size);if(owned)deallocate((void *)old);
 return out;
}
static void *native_string_assign(void *out,const char *value){
 return native_string_assign_n(out,value,native_string_length(value));
}
static void *native_string_copy_ctor(void *out,const void *source){
 u64 size;const u8 *data=native_string_data(source,&size);
 return native_string_construct(out,data,size);
}
static void *native_string_copy_assign(void *out,const void *source){
 if(out==source)return out;
 u64 size;const u8 *data=native_string_data(source,&size);
 return native_string_assign_n(out,data,size);
}
static void *native_string_insert_n(void *out,u64 at,const void *bytes,u64 count){
 u64 size;const u8 *data=native_string_data(out,&size);
 require(bytes&&at<=size&&count<=NATIVE_STRING_MAX-size,"native_string_insert_bound");
 u8 result[4096];copy(result,data,at);copy(result+at,bytes,count);
 copy(result+at+count,data+at,size-at);
 return native_string_assign_n(out,result,size+count);
}
static void *native_string_insert(void *out,u64 at,const char *bytes){
 return native_string_insert_n(out,at,bytes,native_string_length(bytes));
}
static void *native_string_append_n(void *out,const void *bytes,u64 count){
 u64 size;native_string_data(out,&size);return native_string_insert_n(out,size,bytes,count);
}
static void *native_string_append(void *out,const char *bytes){
 return native_string_append_n(out,bytes,native_string_length(bytes));
}
static void native_string_push(void *out,char value){native_string_append_n(out,&value,1);}
static void native_string_resize(void *out,u64 size,char value){
 u64 old_size;const u8 *data=native_string_data(out,&old_size);
 require(size<=NATIVE_STRING_MAX,"native_string_resize_bound");
 u8 result[4096];u64 kept=size<old_size?size:old_size;
 copy(result,data,kept);fill(result+kept,(u8)value,size-kept);
 native_string_assign_n(out,result,size);
}
__attribute__((used)) static void native_to_string_impl(int value,void *out){
 char digits[12];u64 count=0,magnitude=value<0?(u64)-(long)value:(u64)value;
 do{digits[count++]=(char)('0'+magnitude%10);magnitude/=10;}while(magnitude);
 if(value<0)digits[count++]='-';
 for(u64 i=0;i<count/2;i++){char t=digits[i];digits[i]=digits[count-1-i];digits[count-1-i]=t;}
 native_string_construct(out,digits,count);
}
__attribute__((naked)) static void native_to_string(void){
 __asm__ volatile("mov x1, x8\nb native_to_string_impl");
}
static int memory_compare(const u8 *a,const u8 *b,u64 n){require(n<=ARENA_MAX_REQUEST,"memcmp_bound");for(u64 i=0;i<n;i++)if(a[i]!=b[i])return (int)a[i]-b[i];return 0;}
#ifdef CONTROL_EXPERIMENT
/* Emit every native effect in call order. No whole-session operation quota or
 * per-OP eight-effect queue can drop a later original firmware send. */
static void effect(u32 fid,const void *value,u32 n){
 require(value&&n>0&&n<=1024&&current_id&&session_epoch,"effect_bound");
 text(fid?"AUTO ":"NET ");number(current_id);text(" ");number(session_epoch);text(" ");
 if(fid){number(fid);text(" ");}hex(value,n);text("\n");
}
static void native_network_effect(u32 command,const void *value,u32 n){
 require(command>0 && command<=65535 && value && n>0 && n<=1024 &&
         pending_network_tail>=pending_network_head &&
         pending_network_tail-pending_network_head<16 &&
         pending_network_tail!=(u64)-1,"network_completion_queue");
 pending_network[pending_network_tail++%16]=command;
 text("NET ");number(current_id);text(" ");number(session_epoch);
 text(" ");number(command);text(" ");hex(value,n);text("\n");
}
static void native_network_sent(u32 command){
 require(native_sender && pending_network_head<pending_network_tail &&
         pending_network[pending_network_head%16]==command,
         "network_completion_order");
 u8 *listener=(u8 *)*(u64 *)(native_sender+0x38);
 require(listener==(u8 *)local_send_listener &&
         *(u64 *)(listener+0x10)==(u64)object &&
         *(u64 *)listener==(u64)(base+0x8b8b0) &&
         *(u64 *)(base+0x8b8e0)==(u64)(base+0x47d54),
         "network_completion_listener");
 pending_network_head++;
 ((void (*)(void *,u32,u32))(base+0x47d54))(listener,1,command);
}
static void native_network_response_ready(u32 command){
 for(u64 at=pending_network_head;at<pending_network_tail;at++)
  require(pending_network[at%16]!=command,"network_completion_missing");
}
#endif
static void subscription(void *self,u32 device,u32 fid,const void *value,u32 n){
#ifdef CONTROL_EXPERIMENT
 if(fid==0xaa000004 || fid==0xaa00001e || fid==0xaa000102){
  require((fid==0xaa000102 || env_ready) && self==object &&
          device==1034 && value &&
          (fid==0xaa000102?n==2:n<=256),"control_write_boundary");
  effect(fid,value,n);control_writes++;return;}
#endif
 require(self==object && device==1034 && fid==0xaa000023 && value &&
         n>=8 && n<=512 && n%8==0,"subscription_boundary");
 text("CALL ");number(current_id);text(" ");number(session_epoch);
 text(" SDK_CONFIG ");hex(value,n);text("\n");
 call_ok_reply();subscription_bytes=n;
}
__attribute__((naked)) static void singleton(void){__asm__("adrp x9, aux\nadd x9, x9, :lo12:aux\nadd x9, x9, #0x400\nstr x9, [x8]\nret");}
__attribute__((unused)) static void send_capture(void *self,u32 cmd){
#ifdef CONTROL_EXPERIMENT
 if(cmd!=511 && cmd!=220){require(self==object && frames>0 && frame_size>0,"control_send_boundary");effect(0,framed,frame_size);return;}
#endif
 require(self==object && (cmd==511 || cmd==220),"send_boundary");sends++;last_command=cmd;
}
/* Thunks replay four original STP prologue instructions, then continue native
 * code. They change no checks or return values; only copy outputs for tests. */
static u32 encode_capture(void *helper,void *value,void *out,u32 n){
 u32 size=((u32 (*)(void *,void *,void *,u32))(base+0x98000))(helper,value,out,n);
 require(size>0 && size<=sizeof(framed),"framed_bound");copy(framed,out,size);frame_size=size;frames++;return size;
}
static u32 decode_capture(void *helper,u32 type,u32 source,const void *in,u32 n,u16 *cmd,u8 *flag,u8 *version,u8 *out,u16 *size){
 const void *caller=__builtin_return_address(0);
 require(type==3 && source==0,"decoder_source");
 u32 ok=((u32 (*)(void *,u32,u32,const void *,u32,u16 *,u8 *,u8 *,u8 *,u16 *))(base+0x98040))(helper,type,source,in,n,cmd,flag,version,out,size);
 if(ok&1){
  /* The stored-config parser reads through body[26]; the outer network
   * dispatcher only needs 23. Preserve that distinction for short replies. */
  if(caller==base+0x4e218){
   require(*size>=27,"native_config_item_boundary");
   /* Re-decoding a stored item is internal work, not another received frame.
    * Keep its result with its original caller; don't repeat queue correlation
    * or overwrite the outer RX operation's decoded command/counter. */
   return ok;
  }
#ifdef CONTROL_EXPERIMENT
  if(!expected_command){require(*cmd==201||*cmd==301||*cmd==316||*cmd==415||*cmd==421||*cmd==499||*cmd==505||
                                *cmd==511||*cmd==531||*cmd==532||*cmd==536||
                                *cmd==542||*cmd==552||*cmd==599||*cmd==610,"control_command_boundary");
   if(*cmd==531){
    require(active_config_event&&config_handler_registered&&*version==0,
            "config_response_boundary");
    native_network_response_ready(531);
   }
   if(*cmd==542||*cmd==552){
    require(active_config_event&&config_handler_registered&&
            *cmd==(aux[0]?552:542)&&*size>=23,
            "native_config_reply_boundary");
    native_network_response_ready(aux[0]?551:541);
   }
   if(*cmd==301){
    /* The native 301 branch reads its status at body+17.  Status 1 also
     * reads a byte and BE halfword through body+20 before arming GPS. */
    require(*size>=18 && (out[17]!=1 || *size>=21),"native_301_body_boundary");
    native_network_response_ready(300);
   }
   if(*cmd==505){
    /* The original handler uses body+17 and body+19 without a local check. */
    require(*size>=20,"native_505_body_boundary");
    native_network_response_ready(505);
   }
   if(*cmd==316){
    /* Original registration-state handler reads body[17..19]. Status 1
     * updates its private latch; onboarding broadcasts on some status 2
     * branches remain an unsupported external dependency. */
    require(*size>=20,"native_316_body_boundary");
    native_network_response_ready(316);
   }
   /* Original report acknowledgements retain their own bookkeeping. Their
    * branches index no body bytes. Do not process a reply while the matching
    * write is incomplete; original handlers own other correlation checks. */
   if(*cmd==415||*cmd==421||*cmd==499||*cmd==599||*cmd==610)
    native_network_response_ready(*cmd);
   if(*cmd==532)require(env_ready && *version==0 && (*flag==254||*flag==4) && *size>=20 && *size<=64,"generic_532_boundary");
   if(*cmd==536)require(env_ready && *version==0 && *flag==254 && *size==1,"wake_536_boundary");
  }else{require(*cmd==expected_command,"command_boundary");}
#else
  require(*cmd==expected_command,"command_boundary");
#endif
  if(*cmd==511)require(*flag==254 && *version<=1 && *size==17,"status_request_boundary");require(*size<=sizeof(decoded),"decoded_bound");copy(decoded,out,*size);decoded_size=*size;decoded_command=*cmd;decodes++;
 }
 return ok;
}
static void body_capture(void *helper,void *unused,void *packet,u32 cmd,u32 flag,const void *value,u32 n,u32 version){
 require(cmd>0 && cmd<=65535 && (!n||value) && n<=sizeof(body),"body_boundary");
 copy(body,value,n);body_size=n;body_command=cmd;
 ((void (*)(void *,void *,void *,u32,u32,const void *,u32,u32))(base+0x98080))(helper,unused,packet,cmd,flag,value,n,version);
}
static void jump(u64 at,void *target){*(u32 *)(base+at)=0x58000050;*(u32 *)(base+at+4)=0xd61f0200;*(u64 *)(base+at+8)=(u64)target;}
__attribute__((used,noreturn)) static void native_unavailable_at(u64 address){
 char stage[]="unselected_code_000000";
 u64 offset=address-(u64)base-4;
 require(offset<0x98000,"unselected_code_range");
 for(u32 i=0;i<6;i++)stage[16+i]="0123456789abcdef"[(offset>>(4*(5-i)))&15];
 fail(stage);
}
__attribute__((naked)) static void native_unavailable_entry(void){
 __asm__("mov x0, x30\nb native_unavailable_at");
}
static void ptr(u64 at,void *target){*(u64 *)(base+at)=(u64)target;}
static void thunk(u64 entry,u64 at,void *wrapper){copy(base+at,base+entry,16);jump(at+16,base+entry+16);jump(entry,wrapper);}
#ifdef CONTROL_EXPERIMENT
static int get_int(void *self,u32 device,u32 fid,u32 *out){(void)self;require(out!=0,"getter_output");
 require(device>0&&device<=4096,"integer_device_bound");
 *out=call_integer(device,fid,0);return 0;}
static int get_float(void *self,u32 device,u32 fid,u32 *out){(void)self;
 require(device>0&&device<=4096&&out,"float_getter_bound");
 *out=call_integer(device,fid,1);return 0;}
__attribute__((noinline)) static int native_sprintf_checked(char *out,u64 object_size,const char *format,...){
 /* 0x4ad30 wraps __vsprintf_chk, not snprintf. SIZE_MAX is the compiler's
  * unknown-object-size sentinel. Other original callers use up to 1024.
  * Retain fortified known-size checks plus an overall formatting bound. */
 require(out&&format&&object_size>0&&
         (object_size<=65536||object_size==~(u64)0),"format_shape");
 u64 cap=object_size>4096?4096:object_size;
 if(object_size==~(u64)0){
  cap=0;
  /* The two string-concatenation sites allocate their destination in this
   * arena. Honor its actual remaining allocation, including interior pointers. */
  for(u32 at=0;at<heap.end;){
   struct arena_block *block;
   require(arena_block_at(&heap,at,&block)==ARENA_OK,"format_arena");
   u64 start=(u64)heap.bytes+at+ARENA_HEADER,p=(u64)out;
   if(block->live&&p>=start&&p<start+block->size){cap=start+block->size-p;break;}
   at+=ARENA_HEADER+block->size;
  }
  /* The only stack call with unknown size prints one LDRB-loaded byte at
   * 0x6179c and advances by two. Each iteration needs exactly three bytes. */
  if(!cap&&__builtin_return_address(0)==base+0x617a0&&equal(format,"%02x",5))cap=3;
  require(cap>0,"format_unknown_object");
 }
 __builtin_va_list args;__builtin_va_start(args,format);
 u32 at=0;
 for(u32 i=0;format[i];i++){
  require(i<256,"format_string_bound");
  if(format[i]!='%'){require(at+1<cap,"format_capacity");out[at++]=format[i];continue;}
  i++;if(format[i]=='%'){require(at+1<cap,"format_capacity");out[at++]='%';continue;}
  u32 zero=0,width=0;
  if(format[i]=='0'){zero=1;i++;}
  while(format[i]>='0'&&format[i]<='9'){
   width=width*10+(format[i++]-'0');require(width<=16,"format_width");}
  char digits[24];const char *value=digits;u32 n=0;
  if(format[i]=='s'){
   value=__builtin_va_arg(args,const char *);require(value!=0,"format_string_null");
   while(n<cap-at&&value[n])n++;
   require(n<cap-at&&!width,"format_string_capacity");
  }else if(format[i]=='d'||format[i]=='i'){
   n=signed_decimal(digits,(u32)__builtin_va_arg(args,int));
  }else if(format[i]=='u'){
   n=write_decimal(digits,(u32)__builtin_va_arg(args,unsigned));digits[n]=0;
  }else if(format[i]=='x'||format[i]=='X'){
   u32 v=(u32)__builtin_va_arg(args,unsigned);char reverse[8];
   const char *alphabet=format[i]=='x'?"0123456789abcdef":"0123456789ABCDEF";
   do{reverse[n++]=alphabet[v&15];v>>=4;}while(v);
   for(u32 j=0;j<n;j++)digits[j]=reverse[n-1-j];digits[n]=0;
  }else fail("format_conversion_boundary");
  require(at+(width>n?width:n)<cap,"format_capacity");
  if(zero&&width>n&&value[0]=='-'){
   out[at++]='-';value++;n--;width--;
  }
  for(u32 p=n;p<width;p++)out[at++]=zero?'0':' ';
  copy(out+at,value,n);at+=n;
 }
 out[at]=0;__builtin_va_end(args);return (int)at;
}
static int parse_native_integer(const char *input){
 require(input!=0,"atoi_boundary");int negative=0;if(*input=='-'){negative=1;input++;}
 require(*input,"atoi_empty");u32 number=0;for(;*input;input++){
  require(*input>='0'&&*input<='9'&&number<=(0x7fffffffu+negative-(*input-'0'))/10,"atoi_bound");
  number=number*10+(*input-'0');}
 return negative?-(int)number:(int)number;
}
static void require_property_ack(void){
 char answer[80];require(line(answer,sizeof(answer)),"property_ack_eof");
 char expected[80];u32 at=0;const char *prefix="RET ";
 for(u32 i=0;prefix[i];i++)expected[at++]=prefix[i];
 at+=write_decimal(expected+at,current_id);expected[at++]=' ';
 at+=write_decimal(expected+at,session_epoch);
 prefix=" OK";for(u32 i=0;prefix[i];i++)expected[at++]=prefix[i];expected[at]=0;
 require(equal(answer,expected,at+1),"property_write_failed");
}
static int control_property_set(const char *name,const char *value){
 require(name && bounded_length(name)<=64,"property_write_shape");
 if(!value){
  require(equal(name,"persist.sys.cloud.user_id",26)||
          equal(name,"sys.cloud.unlock_uuid",22)||
          equal(name,"persist.sys.cloud_412_data",27),"property_null_boundary");
  text("CALL ");number(current_id);text(" ");number(session_epoch);
  text(" PROPERTY_SET_NULL ");hex((const u8 *)name,(u32)length(name));text("\n");
  require_property_ack();return 0;
 }
 require(bounded_length(value)<=127,"property_value_bound");
 if(equal(name,"persist.sys.edge.enable.sre",28)){
  require(equal(value,"0",2)||equal(value,"1",2),"config_property_value");
  text("CALL ");number(current_id);text(" ");number(session_epoch);
  text(" PROPERTY_SET_STATUS ");hex((const u8 *)name,(u32)length(name));text(" ");
  hex((const u8 *)value,(u32)length(value));text("\n");
  u32 status=signed_input(call_reply("VALUE"));
  require(status==0||status==(u32)-1,"property_status_boundary");
  /* Return the adapter's setter status, including its explicit policy refusal.
   * Original 0x48018 ignores the return code; no successful write is invented. */
  return (int)status;
 }
 if(equal(name,"sys.tcp_step",13)){
  /* Original sender completion 0x47d54 publishes 1/3/5 for 211/200/220;
   * 0/4/6 are reached by the original connection state handlers. This is
   * private state of this copied engine, never the stock daemon property. */
  require(equal(value,"0",2)||equal(value,"1",2)||equal(value,"3",2)||
          equal(value,"4",2)||equal(value,"5",2)||equal(value,"6",2),
          "tcp_step_value");
  private_tcp_step=value[0];return 0;
 }
 if(equal(name,"sys.tcp_connect_status",23)){
  require(equal(value,"0",2)||equal(value,"1",2),"tcp_connect_value");
  if(private_tcp_connect_status!=value[0]){
   /* The stock private status setter is the source of this owner-local
    * transition.  A synchronous boundary lets the owner retire its TLS
    * stream on disconnect before the original socket destructor continues;
    * an unacknowledged transition never becomes a successful native result. */
   text("CALL ");number(current_id);text(" ");number(session_epoch);
   text(" LINK_STATE ");text(value);text("\n");
   call_ok_reply();private_tcp_connect_status=value[0];
  }
  return 0;
 }
 int unlock_index=equal(name,"sys.cloud.unlock_index",23);
 if(unlock_index){require(wake_ready&&(wake_valid&4)&&wake_values[2]<=255,"unlock_index_source");
  require(parse_native_integer(value)==(int)(wake_values[2]<255?wake_values[2]+1:0),"unlock_index_transition");}
 /* Return libproperty's real status to the original caller. Unsupported
  * shared writes are refused with -1, never acknowledged or converted into
  * an unconditional session abort when the original caller ignores status. */
 require(current_id && session_epoch,"property_write_operation");
 text("CALL ");number(current_id);text(" ");number(session_epoch);text(" PROPERTY_SET_RESULT ");
 hex((const u8 *)name,(u32)length(name));text(" ");
 if(value[0])hex((const u8 *)value,(u32)length(value));else text("-");text("\n");
 u32 status=signed_input(call_reply("VALUE"));
 require(status==0||status==(u32)-1,"property_status_boundary");
 if(!status&&unlock_index)wake_values[2]=(wake_values[2]<255?wake_values[2]+1:0);
 return (int)status;
}
static void result_send(void *self,u32 signal){
 require(self==native_sender,"result_send_self");
 require(signal>0&&signal<=65535,"result_send_signal");
 if(signal==201){
  ((void (*)(void *,u32))(base+0x98100))(self,signal);
  return;
 }
 /* 0x4aedc has already made the original enable/network-state decision and
  * the original producer has encoded the complete bootstrap frame. The
  * platform-owned primary TLS channel replaces only the sender's socket and
  * route machinery; it must deliver this opaque frame or abort the session. */
 /* 0x2154 selects the original fixed-size sender buffer at GOT 0x8ef28
  * through 0x73c94, not a body_capture frame with command 0x2154. Keep
  * its original queue/transport path instead of emitting a fabricated NET. */
 if(signal!=1&&signal!=709&&signal!=197&&signal!=0x2154){
  require(frames>0&&frame_size>0&&body_command==signal,
          "bootstrap_frame_boundary");
  native_network_effect(signal,framed,frame_size);
  sends++;last_command=signal;return;}
 ((void (*)(void *,u32))(base+0x98100))(self,signal);
 if(signal==1)control_replies++;
}
static void result_journal(void *self,u32 command,u32 flag,u32 ikey){
 ((void (*)(void *,u32,u32,u32))(base+0x980c0))(self,command,flag,ikey);
 terminal=flag;
}
/* The detached object has no system_server/private listener registration.
 * Execute the original dispatcher for its empty-vector branch, and reject
 * any nonempty vector before an unmodeled Binder callback can be skipped. */
static void native_listener_dispatch(void *self,u32 event){
 require(self==object && *(u64 *)((u8 *)self+0x2e8)==0,
         "listener_bridge_unqualified");
 if(event==3){
  require(*(u64 *)((u8 *)self+0x2e0)==0 && !local_alarm_handle,
          "alarm_listener_overlap");
  local_alarm_vtable[3+4]=(u64)native_alarm_start; /* vtable +0x20 */
  local_alarm_vtable[3+6]=(u64)native_alarm_interval; /* +0x30 */
  local_alarm_object[0]=(u64)(local_alarm_vtable+3);
  local_alarm_vector[0]=(u64)local_alarm_object;
  *(u64 *)((u8 *)self+0x2e0)=(u64)local_alarm_vector;
  *(u64 *)((u8 *)self+0x2e8)=1;
 }
 ((void (*)(void *,u32))(base+0x98140))(self,event);
 if(event==3){
  require(local_alarm_handle && local_alarm_interval==70,
          "alarm_original_dispatch_missing");
  *(u64 *)((u8 *)self+0x2e0)=0;
  *(u64 *)((u8 *)self+0x2e8)=0;
 }
}
/* Enter only the original 0x48c6c..0x48cb4 CloudControl constructor block.
 * It formats fallback "1", calls Android property_get and atoi, then stores
 * +0x594. The detached constructor's EventBus/Binder/worker setup is separate
 * and remains unqualified; this slice has no dependency on their state. */
__attribute__((naked)) static void ctor_property_entry(void *self __attribute__((unused)),
                                                       void *target __attribute__((unused))){
 __asm__ volatile("sub sp, sp, #0xe0\n"
                  "stp x20, x30, [sp, #0xc0]\n"
                  "mov x20, x0\n"
                  "br x1\n");
}
__attribute__((naked)) static void ctor_property_finish(void){
 __asm__ volatile("ldp x20, x30, [sp, #0xc0]\n"
                  "add sp, sp, #0xe0\n"
                  "ret\n");
}
#endif
static void prepare(void){
 require(native_image_end-native_image_start==0x98000,"image_size");long mapping=syscall6(222,0,0x99000,3,0x22,-1,0);require((u64)mapping<(u64)-4095,"mmap");base=(u8 *)mapping;copy(base,native_image_start,0x98000);
 jump(0x98200,native_unavailable_entry);
 native_tcb[5]=(u64)base ^ 0x7d5fa65b53a91c47UL;
 __asm__ volatile("msr tpidr_el0, %0"::"r"(native_tcb):"memory");
 for(u64 i=0;i<sizeof(RELATIVE_RELOCS)/sizeof(RELATIVE_RELOCS[0]);i++)
  *(u64 *)(base+RELATIVE_RELOCS[i][0])=(u64)(base+RELATIVE_RELOCS[i][1]);
 /* 0x72718 only formats hex bytes for the discarded debug logger: each
  * caller passes its result solely to 0x883c0 and then frees the buffer.
  * Original 0x54320 reads BODYWORK/0x12d0002a and refreshes its object state.
  * Its zero-state cache path uses the same read-only file boundary; the
  * separate live POWER/MCU guard still controls this profile's lifetime. */
 const u64 noops[]={0x883c0,0x883f0,0x6ecf8,0x888b0,0x88dd0,0x7e934,0x7e97c,0x88990,0x7c920,0x88410,0x39288,0x72718};
 for(u64 i=0;i<sizeof(noops)/sizeof(noops[0]);i++)jump(noops[i],nothing);
 jump(0x88470,native_cpp_allocate);jump(0x88590,native_cpp_allocate);jump(0x88460,deallocate);jump(0x885b0,deallocate);jump(0x88570,bounded_move);jump(0x88960,memory_compare);jump(0x88600,native_strlen_chk);jump(0x890a0,string_copy);jump(0x699a4,nonce);jump(0x88c60,native_dns);jump(0x88c50,native_inet_ntop);jump(0x88560,bounded_copy);jump(0x887f0,checked_copy);jump(0x885a0,bounded_set);jump(0x88620,native_string_length);jump(0x89010,bounded_compare);jump(0x88550,native_strcmp);
 jump(0x88610,bounded_strncpy);
 jump(0x889d0,native_vector_ctor);jump(0x889f0,native_sender_cond_init);
 jump(0x88970,native_alarm_index);jump(0x88cd0,native_alarm_add);
 jump(0x88980,native_alarm_edit);
 jump(0x88a10,native_sender_mutex_init);
 jump(0x89070,native_vector_capacity);
 jump(0x89080,native_vector_insert);jump(0x89090,native_vector_edit);
 jump(0x88b50,native_vector_remove);jump(0x889b0,native_sender_signal);
 jump(0x80598,native_secondary_ctor);jump(0x80c50,native_secondary_connect);
 jump(0x82b98,native_secondary_write);jump(0x80b24,native_secondary_close);
 jump(0x88640,property);jump(0x88f60,current_time);jump(0x4ab90,subscription);jump(0x6dac4,singleton);
 jump(0x887b0,native_string_assign);
 jump(0x888f0,native_string_copy_ctor);jump(0x887c0,native_string_copy_assign);
 jump(0x88750,native_to_string);jump(0x88760,native_string_insert);
 jump(0x88770,native_string_append);jump(0x888e0,native_string_append_n);
 jump(0x89000,native_string_assign_n);jump(0x886b0,native_string_push);
 jump(0x886c0,native_string_resize);
 thunk(0x6e858,0x98000,encode_capture);thunk(0x727bc,0x98040,decode_capture);thunk(0x6e3b0,0x98080,body_capture);
#ifdef CONTROL_EXPERIMENT
 /* Original 0x48698 owns integer-property fallback and conversion. */
 jump(0x88880,get_int);jump(0x88cc0,get_float);
 jump(0x88f90,native_timer_delete);jump(0x88fa0,native_timer_create);jump(0x88fb0,native_timer_settime);
 jump(0x88b70,native_set_int);jump(0x88b60,native_get_buffer);jump(0x883d0,deallocate);jump(0x88b90,native_mutex_lock);
 jump(0x88ba0,native_steady_clock);jump(0x88bb0,native_condition_wait);
 jump(0x88bc0,native_mutex_unlock);
 jump(0x88a20,native_thread_attr);jump(0x88a30,native_thread_detach);
 jump(0x88a40,native_thread_create);jump(0x88a50,native_thread_attr);
 jump(0x88580,native_usleep);
 jump(0x88d90,native_notify_all);
 jump(0x88d80,event_publisher_unqualified);
 jump(0x88430,timer_guard_acquire);jump(0x88440,timer_guard_release);
 jump(0x884c0,timer_manager_construct);jump(0x88390,timer_manager_atexit);
 jump(0x884b0,timer_manager_create);jump(0x884d0,timer_event_stop);
 jump(0x884e0,timer_event_start);
 jump(0x88500,timer_event_pause);jump(0x88510,timer_event_restart);
 jump(0x884f0,native_system_clock_now);
 jump(0x88890,nothing);jump(0x71888,nothing);jump(0x4ad30,native_sprintf_checked);jump(0x885c0,control_property_set);jump(0x88a00,parse_native_integer);
 jump(0x88b80,native_sysinfo);
 jump(0x88f70,native_localtime);jump(0x88f80,native_mktime);
 jump(0x89230,native_pow);
 jump(0x89050,native_strsep);jump(0x89060,native_atof);
 jump(0x88c70,native_strncpy_chk2);
 jump(0x887d0,native_file_access);jump(0x887e0,native_file_remove);
 jump(0x88810,native_fopen);jump(0x88820,native_file_chmod);
 jump(0x88830,native_file_seek);jump(0x88840,native_fwrite);
 jump(0x88dc0,native_fwrite_checked);jump(0x88bf0,native_fread);
 jump(0x88d30,native_feof);jump(0x88850,native_fclose);
 ptr(0x90df0,allocate);ptr(0x90df8,deallocate);
 thunk(0x54e20,0x980c0,result_journal);
 thunk(0x739a0,0x98100,result_send);
 thunk(0x48280,0x98140,native_listener_dispatch);
 jump(0x48cb8,ctor_property_finish);
 ptr(0x8ef28,aux+0x3000);ptr(0x8ef30,aux+0x4000);ptr(0x8ebf8,aux+0x5000);ptr(0x8ef40,aux+0x6800);
 ptr(0x8ec20,base+0x4a480);ptr(0x8ed90,base+0x5eef0);
 ptr(0x8ecb0,base+0x91190);ptr(0x91190,object);ptr(0x8edf0,base+0x61230);
 ptr(0x8ec40,secondary);ptr(0x8ebb0,base+0x8b280);ptr(0x8eca8,base+0x8b538);
 ptr(0x8f010,native_packet_scratch);
 ptr(0x8eee8,native_502_scratch);
 ptr(0x8ecd0,local_sre_empty_sink);
 ptr(0x8ecc8,&local_sre_sink_ready);
 /* 0x5fe04 addresses this BSS vector directly; 0x5de60 reaches it through GOT. */
 ptr(0x8ec98,base+0x910f8);
#endif
 aux[0]=1;ptr(0x8ed00,aux);ptr(0x8eba8,aux+8);ptr(0x8ec00,vin);
 /* DATA consumers retain their relocated BSS objects, vtables and lazy
  * constructors. They must not appear initialized through a shared dummy
  * guard or discard data through a fabricated vtable. */
 /* Keep the original BSS vector identity.  0x62100 reaches it through GOT,
  * while the configuration replay also addresses 0x91128 directly. */
 ptr(0x8ed18,base+0x91128);
 ptr(0x8eec8,aux+0x6000);ptr(0x8eda8,aux+0x8000);ptr(0x8eed0,aux+0x9000);ptr(0x8ecc0,aux+0xa000);
 ptr(0x8eef0,aux+0x1000);ptr(0x8eef8,aux+0x2000);
 for(u64 at=0;at<0x99000;at+=64)__asm__ volatile("dc cvau, %0"::"r"(base+at):"memory");
 __asm__ volatile("dsb ish":::"memory");for(u64 at=0;at<0x99000;at+=64)__asm__ volatile("ic ivau, %0"::"r"(base+at):"memory");__asm__ volatile("dsb ish\nisb":::"memory");
 require(syscall6(226,(long)base,0x99000,1,0,0,0)==0,"image_readonly");
 for(u64 i=0;i<sizeof(WRITE_PAGES)/sizeof(WRITE_PAGES[0]);i++)
  require(syscall6(226,(long)(base+WRITE_PAGES[i]),0x1000,3,0,0,0)==0,"data_readwrite");
 for(u64 i=0;i<sizeof(EXEC_PAGES)/sizeof(EXEC_PAGES[0]);i++)require(syscall6(226,(long)(base+EXEC_PAGES[i]),0x1000,5,0,0,0)==0,"code_readexecute");
}
struct filter{u16 code;u8 jt,jf;u32 k;};struct program{u16 len;const struct filter *filter;};
#define ST(c,k) {c,0,0,k}
#define JE(k,t,f) {0x15,t,f,k}
static void restrict_process(void){
 const struct filter code[]={ST(0x20,4),JE(0xc00000b7,1,0),ST(0x06,0x80000000),ST(0x20,0),
  JE(93,12,0),JE(94,11,0),JE(101,10,0),JE(63,2,0),JE(64,4,0),ST(0x06,0x00050001),
  ST(0x20,16),JE(0,5,0),ST(0x06,0x00050001),ST(0x20,16),JE(1,2,0),JE(2,1,0),
  ST(0x06,0x00050001),ST(0x06,0x7fff0000)};
 const struct program p={sizeof(code)/sizeof(code[0]),code};require(syscall6(167,38,1,0,0,0,0)==0,"no_new_privs");require(syscall6(167,22,2,(long)&p,0,0,0)==0,"seccomp");
 require(syscall6(198,2,1,0,0,0,0)==-1 && syscall6(29,-1,0,0,0,0,0)==-1 && syscall6(56,-100,(long)"/dev/null",0,0,0,0)==-1 && syscall6(220,0,0,0,0,0,0)==-1,"isolation");
}
static int digit(char c){if(c>='0' && c<='9')return c-'0';if(c>='a' && c<='f')return c-'a'+10;return -1;}
static u32 unhex(const char *s,u8 *out,u32 cap){u64 n=length(s);require(n%2==0 && n/2<=cap,"hex_bound");for(u32 i=0;i<n/2;i++){int a=digit(s[2*i]),b=digit(s[2*i+1]);require(a>=0 && b>=0,"hex_digit");out[i]=(a<<4)|b;}return n/2;}
static void hex(const u8 *in,u32 n){static const char ds[]="0123456789abcdef";char line[2049];require(n<=1024,"hex_output");for(u32 i=0;i<n;i++){line[2*i]=ds[in[i]>>4];line[2*i+1]=ds[in[i]&15];}line[2*n]=0;text(line);}
static void number(u32 n){char s[16];u32 at=15;s[at]=0;do{s[--at]='0'+n%10;n/=10;}while(n);text(s+at);}
static void number64(u64 n){char s[24];u32 at=23;s[at]=0;do{s[--at]='0'+n%10;n/=10;}while(n);text(s+at);}
static void result_prefix(const char *kind){text("RESULT ");number(current_id);text(" ");number(session_epoch);text(" ");text(kind);text(" ");}
static void done(const char *kind){text("DONE ");number(current_id);text(" ");number(session_epoch);text(" ");text(kind);text("\n");}
static u64 decimal(const char *s){u64 n=0;require(*s,"decimal_empty");for(;*s;s++){require(*s>='0'&&*s<='9'&&n<=((u64)-1-(*s-'0'))/10,"decimal_bound");n=n*10+(*s-'0');}return n;}
static u32 signed_input(const char *s){int negative=*s=='-';if(negative)s++;
 u64 value=decimal(s);require(value<=(negative?2147483648ul:2147483647ul),"signed_integer_bound");
 return negative?(u32)(~(u32)value+1):(u32)value;}
static char *token(char **cursor){char *start=*cursor;require(*start,"token_missing");while(**cursor && **cursor!=' ')(*cursor)++;if(**cursor){**cursor=0;(*cursor)++;}return start;}
#ifdef CONTROL_EXPERIMENT
static void command_result(void){text("DONE ");number(current_id);text(" ");number(session_epoch);text(" ");number(decoded_command);text(" ");number(control_replies);text(" ");number(terminal);text("\n");}
#endif
static int line(char *out,u32 cap){u32 n=0;for(;;){char c;long got=syscall6(63,0,(long)&c,1,0,0,0);if(!got)return 0;require(got==1,"stdin");if(c=='\n'){out[n]=0;return 1;}require(n+1<cap && c>=32 && c<=126,"line_bound");out[n++]=c;}}
static void receive(u32 command,const char *arg){u32 n=unhex(arg,input,sizeof(input));require(n>=53,"frame_short");expected_command=command;decodes=0;sends=0;frames=0;frame_size=0;
 if(command)native_network_response_ready(command);
#ifdef CONTROL_EXPERIMENT
 /* Completion is an event from this exchange, not a level carried from a
  * previous command. Original native busy/correlation state is untouched. */
 terminal=0;control_replies=0;control_writes=0;
#endif
 ((void (*)(void *,u32,const void *,u32))(base+0x573ec))(object,0,input,n);
 require(decodes==1 && (!command || decoded_command==command),"native_decode_rejected");
#ifdef CONTROL_EXPERIMENT
 if(!command && decoded_command!=511){command_result();env_ready=0;return;}
 if(!command)command=decoded_command;
#endif
 if(command==200){require(native_sender!=0,"discovery_sender_absent");
  u32 native_port=*(u16 *)(native_sender+0x5c);
  require(native_port && domain[0] && sends==1 && last_command==220,"discovery_failed");
  result_prefix("ENDPOINT");text(domain);text(" ");number(native_port);text("\n");}
 else if(command==220){result_prefix("LOGIN");number(object[0x2d0]);text("\n");}
 else if(command==211){result_prefix("REG");number(object[0x35c]);text("\n");}
 else {require(command==511 && callbacks>0 && sends==1 && frames==1 && body_size==120,"status_failed");result_prefix("STATUS");hex(framed,frame_size);text("\n");}
 done("RX");
}
__attribute__((noreturn)) void probe_main(void){
 u64 core[2]={0,0};require(syscall6(261,0,4,(long)core,0,0,0)==0,"core_limit");require(syscall6(167,4,0,0,0,0,0)==0,"dumpable");
 long parent=syscall6(173,0,0,0,0,0,0);require(parent>1,"parent_missing");
 require(syscall6(167,1,9,0,0,0,0)==0 && syscall6(173,0,0,0,0,0,0)==parent,"parent_race");
 __asm__ volatile("msr tpidr_el0, %0"::"r"(tls):"memory");arena_init(&heap,arena,sizeof(arena));prepare();restrict_process();text("READY\nCAPS PROTO2=1 REG=1 DATA=1 CONTROL532=0 WAKE536=0 MCU_STATE=0 POSIX_TIMERS=0 SUB5_TIMER=0 POST_LOGIN=0 HEARTBEAT=1 STOCK_LIFECYCLE_BRIDGE=0 CONTROL_AWAKE=1 WAKE_ACK_AWAKE=1 TIMERS_AWAKE=1 POST_LOGIN_AWAKE=1\n");char cmd[2100];u32 mask=0;
 while(line(cmd,sizeof(cmd))){char *arg=cmd;while(*arg && *arg!=' ')arg++;if(*arg)*arg++=0;
  if(equal(cmd,"OP",3)){
   char *cursor=arg;u64 id=decimal(token(&cursor)),epoch=decimal(token(&cursor));
   require(id>0 && id<=0xffffffffu && epoch>0 && epoch<=0xffffffffu,"operation_identity");
   if(session_epoch)require(session_epoch==(u32)epoch,"session_epoch_changed");
   else session_epoch=(u32)epoch;
   current_id=(u32)id;char *verb=token(&cursor);
   u64 vn=length(verb);require(vn<sizeof(cmd),"verb_bound");copy(cmd,verb,vn+1);arg=cursor;
  }
  if(equal(cmd,"QUIT",5))break;
#ifdef CONTROL_EXPERIMENT
  if(equal(cmd,"TICK",5)){require(prepared&&current_id,"tick_not_ready");
   char *cursor=arg;u64 elapsed=decimal(token(&cursor)),uptime=decimal(token(&cursor)),wall=decimal(token(&cursor));
   require(!*cursor,"tick_shape");frames=0;frame_size=0;
   advance_clocks(elapsed,uptime,wall);run_due_timers();
   done("TICK");continue;}
  if(equal(cmd,"RX",3)){require(prepared&&env_ready&&current_id,"control_not_ready");receive(0,arg);continue;}
  if(equal(cmd,"NETSTATE",9)){
   require(prepared&&current_id,"network_state_not_ready");
   u32 state=signed_input(arg);
   require(state==4 || state==(u32)-5,"virtual_public_state_boundary");
   frames=0;frame_size=0;
   ((void (*)(void *,u32))(base+0x4b694))(object,state);
   done("NETSTATE");continue;}
  if(equal(cmd,"SENT",5)){
   require(prepared&&current_id,"network_completion_not_ready");
   u64 command=decimal(arg);require(command>0&&command<=65535,
                                 "network_completion_command");
   native_network_sent((u32)command);
   done("SENT");continue;}
  if(equal(cmd,"KEEPALIVE",10)){
   require(prepared&&current_id&&clock_ready&&object[0x2d0]==1&&
           equal(arg,"1",2),"keepalive_awake_boundary");
   frames=0;frame_size=0;
   ((void (*)(void *,u32))(base+0x539d8))(object,1);
   done("KEEPALIVE");continue;}
  if(equal(cmd,"STATE",6)){require(prepared&&env_ready&&wake_ready&&current_id,"state_not_ready");
   u64 value=decimal(arg);require(value<=1,"state_value_boundary");frames=0;frame_size=0;
   ((void (*)(void *,u32))(base+0x5d87c))(object,(u32)value);
   done("STATE");env_ready=0;wake_ready=0;continue;}
  if(equal(cmd,"INT",4)){require(prepared&&env_ready&&wake_ready&&current_id,"integer_event_not_ready");
   char *cursor=arg;u64 device=decimal(token(&cursor)),fid=decimal(token(&cursor));
   u32 value=signed_input(token(&cursor));
   require(!*cursor && ((device==1005 && fid==0x99000003 && value<=1)||
           (device==1001 && fid==0x12d0002a)||
           (device==1009 && fid==0x34400018)),"integer_event_unqualified");
   frames=0;frame_size=0;
   ((void (*)(void *,u32,u32,u32))(base+0x6822c))(observer,(u32)device,(u32)fid,value);
   done("INT");env_ready=0;wake_ready=0;continue;}
  if(equal(cmd,"MCU",4)){require(prepared&&env_ready&&current_id,"mcu_not_ready");u32 n=unhex(arg,input,sizeof(input));
   int wake=n==7&&input[1]==2&&input[2]==24&&input[5]==1;
   require(wake||(n>=26&&n<=70&&input[1]==2&&input[2]==20),"mcu_generic_boundary");
   require(input[4]==1||input[4]==2||input[4]==3,"mcu_flag_boundary");
   if(!wake)require(equal(input+6,object+0x422e,16),"mcu_correlation_boundary");
   terminal=0;control_replies=0;control_writes=0;frames=0;frame_size=0;
   /* A wake/intermediate callback cannot inherit prior success. */
   /* An interleaved cloud status/wake can change decoded_command while a
    * control is pending. Label this already-bounded MCU envelope itself. */
   decoded_command=wake?536:532;
   ((void (*)(void *,u32,const void *,u32))(base+0x6039c))(object,0x99000004,input,n);command_result();env_ready=0;continue;}
#endif
  if(equal(cmd,"START",6)){require(mask==511 && !prepared && current_id,"init_inputs");
   u8 *secondary_guard=*(u8 **)(base+0x8ec38);
   require(timer_guard_acquire(secondary_guard)==1,"secondary_initialized_twice");
   ((void (*)(void *))(base+0x4404c))(secondary);
   timer_manager_atexit(base+0x442a0,secondary,base+0x90ea0);
   timer_guard_release(secondary_guard);
   *(u64 *)(object+0x30)=(u64)secondary;
   *(u64 *)object=(u64)(base+0x8b550);
   *(u64 *)(observer+0x10)=(u64)object;
   /* Original 0x36b78 copies this +0x2c0 listener into its lazy sender.
    * The selected vtable's +0x30 method is original send_complete 0x47d54. */
   local_send_listener[0]=(u64)(base+0x8b8b0);
   local_send_listener[2]=(u64)object;
   *(u64 *)(object+0x2c0)=(u64)local_send_listener;
   ctor_property_entry(object,base+0x48c6c);
   heartbeat_state=allocate(0x60);fill(heartbeat_state,0,0x60);
   ((void (*)(void *))(base+0x383a8))(heartbeat_state);
   *(u64 *)(object+0x38)=(u64)heartbeat_state;
   /* 0x5bf78..0x5bf88 installs this exact std::function target in stock:
    * vtable 0x8c570, capture CloudControl*, inline callable at +0x20.
    * Invoke the original assignment 0x38898 so expiry uses its native
    * disconnect callback rather than an invented timeout result. */
   u64 disconnect[5]={(u64)(base+0x8c570),(u64)object,0,0,0};
   disconnect[4]=(u64)disconnect;
   ((void (*)(void *,void *))(base+0x38898))(heartbeat_state,disconnect);
   ((void (*)(void *))(base+0x6d9f4))(aux+0x400);copy(aux+0x400+0xe2,uuid,16);copy(aux+0x400+0xf2,key,16);
   object[0x615]=1;*(u32 *)(object+0x294)=0xff;
   ((void (*)(void *))(base+0x61cf0))(object);require(subscription_bytes==176,"table");
   require(((u32 (*)(void *))(base+0x6dcf0))(aux+0x400)==1 && ((u32 (*)(void *))(base+0x72e0c))(aux+0x400)==1,"native_identity");prepared=1;done("START");continue;
  }
  if(cmd[1]==0){u32 n=0;
#ifdef CONTROL_EXPERIMENT
   require(current_id && session_epoch,"untagged_operation");
   if(cmd[0]=='E'){u8 snapshot[36];require(unhex(arg,snapshot,sizeof(snapshot))==sizeof(snapshot),"environment_input");
    copy(env,snapshot,32);copy(&env_valid,snapshot+32,4);
    require((env_valid&0xc3)==0xc3,"state_getter_failed");
    *(u32 *)(object+0x28c)=env[0];*(u32 *)(object+0x290)=env[6];env_ready=1;done("E");continue;}
   if(cmd[0]=='X'){u8 snapshot[16];require(unhex(arg,snapshot,sizeof(snapshot))==sizeof(snapshot),"state_environment_input");
    copy(wake_values,snapshot,12);copy(&wake_valid,snapshot+12,4);wake_ready=1;done("X");continue;}
#endif
   if(cmd[0]=='N'){require(unhex(arg,random_nonce,16)==16,"nonce_input");mask|=128;}
   else if(cmd[0]=='T'){require(unhex(arg,(u8 *)&timestamp,4)==4 && timestamp>0,"timestamp");mask|=64;}
   else if(cmd[0]=='H'){u32 value=0;require(unhex(arg,(u8 *)&value,4)==4 && value<=255,"charging");*(u32 *)(object+0x294)=value;}
   else if(cmd[0]=='I'){require(prepared && (n=unhex(arg,input,74))>=18,"callback_input");((void (*)(void *,const void *,u32))(base+0x627d4))(object,input,n);callbacks++;}
   else if(cmd[0]=='D'||cmd[0]=='L'||cmd[0]=='G'){require(prepared,"not_prepared");frames=0;((void (*)(void *))(base+(cmd[0]=='D'?0x6edac:cmd[0]=='L'?0x6f01c:0x6e00c)))(aux+0x400);require(frames==1,"producer");result_prefix("WIRE");hex(framed,frame_size);text("\n");done(cmd);continue;}
   else {require(!prepared,"identity_frozen");
    if(cmd[0]=='V'){require(unhex(arg,vin,17)==17,"vin");mask|=1;}
    else if(cmd[0]=='K'){require(unhex(arg,key,16)==16,"key");mask|=2;}
    else if(cmd[0]=='U'){require(unhex(arg,uuid,16)==16,"uuid");mask|=4;}
    else if(cmd[0]=='C'){require(unhex(arg,(u8 *)iccid,20)==20,"iccid");for(u32 i=0;i<20;i++)require(iccid[i]>='0'&&iccid[i]<='9',"iccid_digits");mask|=8;}
    else if(cmd[0]=='M'){require(unhex(arg,(u8 *)imsi,15)==15,"imsi");for(u32 i=0;i<15;i++)require(imsi[i]>='0'&&imsi[i]<='9',"imsi_digits");mask|=16;}
    else if(cmd[0]=='S'){n=unhex(arg,(u8 *)serial,91);require(bounded_length(serial)==n,"serial_nul");mask|=32;}
    else if(cmd[0]=='A'){n=unhex(arg,(u8 *)apn_type,31);apn_type[n]=0;
     require(n==10&&equal(apn_type,"double_apn",11),"virtual_public_profile");mask|=256;}
    else fail("unknown_input");
   }
   done(cmd);continue;
  }
  require(prepared,"not_prepared");
  if(equal(cmd,"R200",5))receive(200,arg);
  else if(equal(cmd,"R220",5))receive(220,arg);
  else if(equal(cmd,"R211",5))receive(211,arg);
  else if(equal(cmd,"R511",5))receive(511,arg);
  else fail("unknown_command");
 }
 text("BYE\n");syscall6(94,0,0,0,0,0,0);for(;;){}
}
__asm__(".global _start\n_start:\n bl probe_main\n brk #0\n");
