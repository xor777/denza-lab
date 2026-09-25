/* Isolated original status codec/dispatcher, synthetic session only.
 * No native startup, network, Binder or actuator branches. See paired builder.
 */
typedef unsigned long u64;
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;
extern const u8 native_image_start[], native_image_end[];
#include "native_roundtrip_fixtures.h"
static u8 *base;
static u8 object[0x5000] __attribute__((aligned(16)));
static u8 aux[0x3000] __attribute__((aligned(16)));
static u8 arena[0x10000], input[1024], tls[128], vin[18];
static u8 request[1024], response[1024], framed[1024], decoded[1024];
static u8 packet[0x440] __attribute__((aligned(16)));
static u32 allocated, sends, last_command, subscription_bytes, frames, frame_size;
static u32 decodes, decoded_size, decoded_command, cases;
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
static void phase(const char *s){syscall6(64,2,(long)s,length(s),0,0,0);}
__attribute__((noreturn)) static void fail(const char *why){text("{\"passed\":false,\"stage\":\"");text(why);text("\"}\n");syscall6(94,1,0,0,0,0,0);for(;;){}}
static void require(int ok,const char *why){if(!ok)fail(why);}
static long nothing(void){return 0;}
static void *allocate(u64 n){require(n>0 && n<=4096 && allocated+n<sizeof(arena),"allocation_bound");void *p=arena+allocated;allocated=(allocated+n+15)&~15UL;fill(p,0xcc,n);return p;}
static void *bounded_copy(void *d,const void *s,u64 n){require(n<=4096,"copy_bound");copy(d,s,n);return d;}
static void *checked_copy(void *d,const void *s,u64 n,u64 bound){require(n<=bound,"checked_copy_bound");return bounded_copy(d,s,n);}
static void *bounded_set(void *d,int b,u64 n){require(n<=4096,"set_bound");fill(d,b,n);return d;}
static u64 bounded_length(const char *s){u64 n=0;while(n<128 && s[n])n++;require(n<128,"string_bound");return n;}
static int bounded_compare(const u8 *a,const u8 *b,u64 n){require(n<=17,"compare_bound");for(u64 i=0;i<n;i++){if(a[i]!=b[i])return (int)a[i]-b[i];if(!a[i])break;}return 0;}
static int property(const char *name,char *out,const char *fallback){(void)fallback;require(equal(name,"persist.sys.byd.apn_type",24),"property_boundary");copy(out,"double_apn",11);return 10;}
static long fixture_time(void){return 1700000000;}
static void subscription(void *self,u32 device,u32 fid,const void *value,u32 n){require(self==object && device==1034 && fid==0xaa000023 && value && n==176,"subscription_boundary");subscription_bytes=n;}
__attribute__((naked)) static void singleton(void){__asm__("adrp x9, aux\nadd x9, x9, :lo12:aux\nadd x9, x9, #0x400\nstr x9, [x8]\nret");}
static void send_capture(void *self,u32 cmd){require(self==object && cmd==511,"send_boundary");sends++;last_command=cmd;}
/* Thunks replay four original STP prologue instructions, then continue native
 * code. They change no checks or return values; only copy outputs for tests. */
static u32 encode_capture(void *helper,void *value,void *out,u32 n){
 u32 size=((u32 (*)(void *,void *,void *,u32))(base+0x98000))(helper,value,out,n);
 require(size>0 && size<=sizeof(framed),"framed_bound");copy(framed,out,size);frame_size=size;frames++;return size;
}
static u32 decode_capture(void *helper,u32 type,u32 source,const void *in,u32 n,u16 *cmd,u8 *flag,u8 *version,u8 *out,u16 *size){
 require(type==3 && source==0,"decoder_source");
 u32 ok=((u32 (*)(void *,u32,u32,const void *,u32,u16 *,u8 *,u8 *,u8 *,u16 *))(base+0x98040))(helper,type,source,in,n,cmd,flag,version,out,size);
 if(ok&1){require(*size<=sizeof(decoded),"decoded_bound");copy(decoded,out,*size);decoded_size=*size;decoded_command=*cmd;decodes++;}
 return ok;
}
static void jump(u64 at,void *target){*(u32 *)(base+at)=0x58000050;*(u32 *)(base+at+4)=0xd61f0200;*(u64 *)(base+at+8)=(u64)target;}
static void ptr(u64 at,void *target){*(u64 *)(base+at)=(u64)target;}
static void thunk(u64 entry,u64 at,void *wrapper){copy(base+at,base+entry,16);jump(at+16,base+entry+16);jump(entry,wrapper);}
static void prepare(void){
 require(native_image_end-native_image_start==0x98000,"image_size");long mapping=syscall6(222,0,0x99000,3,0x22,-1,0);require((u64)mapping<(u64)-4095,"mmap");base=(u8 *)mapping;copy(base,native_image_start,0x98000);
 const u64 noops[]={0x883c0,0x883f0,0x885b0,0x6ecf8,0x54320,0x888b0,0x88dd0,0x3f7f4,0x7e934,0x7e97c};
 for(u64 i=0;i<sizeof(noops)/sizeof(noops[0]);i++)jump(noops[i],nothing);
 jump(0x88590,allocate);jump(0x88560,bounded_copy);jump(0x887f0,checked_copy);jump(0x885a0,bounded_set);jump(0x88620,bounded_length);jump(0x89010,bounded_compare);
 jump(0x88640,property);jump(0x88f60,fixture_time);jump(0x4ab90,subscription);jump(0x6dac4,singleton);jump(0x4aedc,send_capture);
 thunk(0x6e858,0x98000,encode_capture);thunk(0x727bc,0x98040,decode_capture);
 aux[0]=1;ptr(0x8ed00,aux);ptr(0x8eba8,aux+8);ptr(0x8ed58,aux);ptr(0x8ee00,aux);ptr(0x8ecc8,aux);ptr(0x8ec00,vin);
 ptr(0x8ed60,aux+0x100);ptr(0x8ee08,aux+0x200);*(u64 *)(aux+0x200)=(u64)(aux+0x300);*(u64 *)(aux+0x328)=(u64)nothing;
 ptr(0x8ed18,aux+0x700); /* Explicit empty auxiliary subscription vector. */
 ptr(0x8eef0,aux+0x1000);ptr(0x8eef8,aux+0x2000);
 for(u64 at=0;at<0x99000;at+=64)__asm__ volatile("dc cvau, %0"::"r"(base+at):"memory");
 __asm__ volatile("dsb ish":::"memory");for(u64 at=0;at<0x99000;at+=64)__asm__ volatile("ic ivau, %0"::"r"(base+at):"memory");__asm__ volatile("dsb ish\nisb":::"memory");
 require(syscall6(226,(long)base,0x99000,1,0,0,0)==0,"image_readonly");
 for(u64 i=0;i<sizeof(EXEC_PAGES)/sizeof(EXEC_PAGES[0]);i++)require(syscall6(226,(long)(base+EXEC_PAGES[i]),0x1000,5,0,0,0)==0,"code_readexecute");
}
struct filter{u16 code;u8 jt,jf;u32 k;};struct program{u16 len;const struct filter *filter;};
#define ST(c,k) {c,0,0,k}
#define JE(k,t,f) {0x15,t,f,k}
static void restrict_process(void){
 const struct filter code[]={ST(0x20,4),JE(0xc00000b7,1,0),ST(0x06,0x80000000),ST(0x20,0),JE(93,6,0),JE(94,5,0),JE(64,0,3),ST(0x20,16),JE(1,2,0),JE(2,1,0),ST(0x06,0x00050001),ST(0x06,0x7fff0000)};
 const struct program p={sizeof(code)/sizeof(code[0]),code};require(syscall6(167,38,1,0,0,0,0)==0,"no_new_privs");require(syscall6(167,22,2,(long)&p,0,0,0)==0,"seccomp_install");
 require(syscall6(198,2,1,0,0,0,0)==-1,"network_not_blocked");require(syscall6(29,-1,0,0,0,0,0)==-1,"binder_not_blocked");require(syscall6(56,-100,(long)"/dev/null",0,0,0,0)==-1,"files_not_blocked");require(syscall6(220,0,0,0,0,0,0)==-1,"process_creation_not_blocked");
}
static void initialize(void){
 fill(object,0,sizeof(object));fill(aux+0x400,0,0x200);allocated=0;sends=0;frames=0;decodes=0;decoded_size=0;
 copy(vin,"TEST123456789ABCD",18);for(u32 i=0;i<16;i++){aux[0x400+0xe2+i]=i+16;aux[0x400+0xf2+i]=i;}
 *(u32 *)(object+0x294)=0xff;object[0x615]=1;object[0x2d0]=1;
 ((void (*)(void *))(base+0x61cf0))(object);require(subscription_bytes==176,"native_table");
}
static void receive(const u8 *value,u32 n){require(n<=sizeof(input),"receive_bound");copy(input,value,n);((void (*)(void *,u32,const void *,u32))(base+0x573ec))(object,0,input,n);}
__attribute__((noreturn)) void probe_main(void){
 u64 core[2]={0,0},cpu[2]={2,2},timer[4]={0,0,5,0};require(syscall6(261,0,4,(long)core,0,0,0)==0,"core_limit");require(syscall6(261,0,0,(long)cpu,0,0,0)==0,"cpu_limit");require(syscall6(103,0,(long)timer,0,0,0,0)==0,"wall_timer");require(syscall6(167,4,0,0,0,0,0)==0,"dumpable");
 __asm__ volatile("msr tpidr_el0, %0"::"r"(tls):"memory");prepare();restrict_process();initialize();phase("initialized\n");
 for(u32 i=0;i<16;i++)input[i]=i+32;
 ((void (*)(void *,void *,void *,u32,u32,const void *,u32,u32))(base+0x6e3b0))(aux+0x400,0,packet,511,254,input,16,1);
 phase("request_body\n");
 u32 request_size=((u32 (*)(void *,void *,void *,u32))(base+0x6e858))(aux+0x400,packet,request,16);
 require(request_size==sizeof(EXPECTED_REQUEST) && equal(request,EXPECTED_REQUEST,request_size),"native_request_matches_offline");phase("request_framed\n");
 initialize();for(u32 i=0;i<BUFFER_COUNT;i++)((void (*)(void *,const void *,u32))(base+0x627d4))(object,BUFFERS[i],BUFFER_SIZES[i]);
 phase("callbacks_ingested\n");receive(request,request_size);require(sends==1 && last_command==511 && frames==1 && decodes==1 && decoded_command==511 && decoded_size==17,"native_request_dispatch");phase("request_dispatched\n");
 for(u32 i=0;i<16;i++)require(decoded[i+1]==i+32,"request_correlation");
 require(frame_size==sizeof(EXPECTED_RESPONSE) && equal(framed,EXPECTED_RESPONSE,frame_size),"native_response_matches_offline");u32 response_size=frame_size;copy(response,framed,response_size);cases++;
 initialize();object[0x2d0]=0;receive(response,response_size);require(!sends && !frames && decodes==1 && decoded_command==511 && decoded_size==121,"response_decode");
 for(u32 i=0;i<16;i++)require(decoded[i+1]==i+32,"response_correlation");require(equal(decoded+17,EXPECTED_BODY,104),"response_body");cases++;
 for(u32 test=0;test<12;test++){
  initialize();copy(input,request,request_size);u32 n=request_size;
  if(test==0)fill(aux+0x400+0xe2,'X',16);if(test==1)fill(aux+0x400+0xf2,'Y',16);if(test==2)copy(vin,"OTHER23456789ABCD",17);
  if(test==3)fill(aux+0x400+0xe2,0,16);if(test==4)fill(aux+0x400+0xf2,0,16);if(test==5)fill(aux+0x400+0xe2,0xa7,16);
  if(test==6)copy(aux+0x400+0xf2,aux+0x400+0xe2,16);if(test==7)object[0x615]=0;if(test==8)object[0x2d0]=0;
  if(test==9)input[request_size-8]^=0x40;if(test==10)input[0]=0;if(test==11)n=4;
  ((void (*)(void *,u32,const void *,u32))(base+0x573ec))(object,0,input,n);
  require(sends==0 && frames==0 && decodes==(test==8),"negative_accepted");cases++;
 }
 require(cases==14,"case_count");text("{\"passed\":true,\"cases\":14,\"original_arm64_executed\":true,\"native_aes_crc_dispatch_and_reply\":true,\"synthetic_session_only\":true,\"network_binder_files_clone_blocked\":true,\"cloud_upload\":false,\"actuator_branches_present\":false}\n");
 syscall6(94,0,0,0,0,0,0);for(;;){}
}
__asm__(".global _start\n_start:\n bl probe_main\n brk #0\n");
