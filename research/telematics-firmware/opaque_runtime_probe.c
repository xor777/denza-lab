/* Local execution of stock opaque-buffer routines; no installed daemon, network,
 * Binder or crypto. Only original code maps vehicle fields. See paired builder.
 */
typedef unsigned long u64;
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;
extern const u8 identity_image_start[], identity_image_end[];
#include "opaque_runtime_fixtures.h"
static u8 *base;
static u8 object[0x5000] __attribute__((aligned(16)));
static u8 aux[0x3000] __attribute__((aligned(16)));
static u8 arena[0x10000], input[512], tls[128], captured[128], saved[128];
static u64 allocated;
static u32 capture_size, captures, sends, last_command, subscription_bytes, cases, ssl_calls;
static int busy, socket_result, stream_mode;
static long syscall6(long n,long a,long b,long c,long d,long e,long f) {
    register long x0 __asm__("x0")=a, x1 __asm__("x1")=b, x2 __asm__("x2")=c;
    register long x3 __asm__("x3")=d, x4 __asm__("x4")=e, x5 __asm__("x5")=f;
    register long x8 __asm__("x8")=n;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5),"r"(x8) : "memory","cc");
    return x0;
}
static u64 length(const char *s) { u64 n=0; while(s[n]) ++n; return n; }
static int equal(const void *a,const void *b,u64 n) {
    const u8 *x=a,*y=b; for(u64 i=0;i<n;i++) if(x[i]!=y[i]) return 0; return 1;
}
static void copy(void *d,const void *s,u64 n) { u8 *x=d; const u8 *y=s; for(u64 i=0;i<n;i++) x[i]=y[i]; }
static void fill(void *d,u8 b,u64 n) { u8 *x=d; for(u64 i=0;i<n;i++) x[i]=b; }
static void write_text(const char *s) { syscall6(64,1,(long)s,length(s),0,0,0); }
__attribute__((noreturn)) static void fail(const char *why) {
    write_text("{\"passed\":false,\"stage\":\""); write_text(why); write_text("\"}\n");
    syscall6(94,1,0,0,0,0,0); for(;;) {}
}
static void require(int v,const char *s) { if(!v) fail(s); }
static long nothing(void) { return 0; }
static long trylock(void *lock) { require(lock==object+0x4c0,"mutex_context");return busy?16:0; }
static void *allocate(u64 n) { require(n>0 && n<=4096 && allocated+n<sizeof(arena),"allocation_bound");void *p=arena+allocated;allocated=(allocated+n+15)&~15UL;fill(p,0xcc,n);return p; }
static void *bounded_copy(void *d,const void *s,u64 n) { require(n<=4096,"copy_bound");copy(d,s,n);return d; }
static void *bounded_set(void *d,int b,u64 n) { require(n<=4096,"set_bound");fill(d,b,n);return d; }
static u64 bounded_length(const char *s) { u64 n=0;while(n<128 && s[n])n++;require(n<128,"string_bound");return n; }
static char *bounded_string(char *d,const char *s) { copy(d,s,bounded_length(s)+1);return d; }
static void subscription(void *self,u32 device,u32 fid,const void *data,u32 n) {
 require(self==object && device==1034 && fid==0xaa000023 && data && n==176,"subscription_boundary");subscription_bytes=n;
}
/* Original getter returns android::sp through AArch64 indirect result register x8. */
__attribute__((naked)) static void singleton(void) { __asm__("adrp x9, aux\nadd x9, x9, :lo12:aux\nadd x9, x9, #0x400\nstr x9, [x8]\nret"); }
static int capture(void *self,void *unused,void *packet,u32 cmd,u32 flag,const u8 *value,u32 n,u32 tail) {
 (void)self;(void)unused;(void)packet;(void)flag;(void)tail;
 require((cmd==511 && n==120)||(cmd==512 && n==104),"report_boundary");copy(captured,value,n);capture_size=n;captures++;return 0;
}
static void send_capture(void *self,u32 cmd) {require(self==object && (cmd==511 || cmd==512),"send_boundary");sends++;last_command=cmd;}
static int ssl_read_stub(void *ssl,void *data,u32 n) {require(ssl==(void *)0x123456 && data==input && n==32,"ssl_read_boundary");ssl_calls++;for(int i=0;i<socket_result;i++)((u8 *)data)[i]=(u8)i;return socket_result;}
static int ssl_write_stub(void *ssl,const void *data,u32 n) {require(ssl==(void *)0x123456 && data==input && n==32,"ssl_write_boundary");ssl_calls++;return socket_result;}
static int ssl_error_stub(void *ssl,int result) {require(ssl==(void *)0x123456 && result==socket_result && result<=0,"ssl_error_boundary");return 2;}
static void jump(u64 at,void *target) {*(u32 *)(base+at)=0x58000050;*(u32 *)(base+at+4)=0xd61f0200;*(u64 *)(base+at+8)=(u64)target;}
static void ptr(u64 at,void *target) {*(u64 *)(base+at)=(u64)target;}
static void prepare_mapping(void) {
 u64 size=identity_image_end-identity_image_start;require(size==0x98000,"image_size");
 long mapping=syscall6(222,0,size,3,0x22,-1,0);require((u64)mapping<(u64)-4095,"mmap");base=(u8 *)mapping;copy(base,identity_image_start,size);
 const u64 noops[]={0x883c0,0x883f0,0x88990,0x885b0,0x6ecf8,0x54320,0x888b0,0x3f7f4,0x6e858};
 for(u64 i=0;i<sizeof(noops)/sizeof(noops[0]);i++)jump(noops[i],nothing);
 jump(0x88dd0,trylock);jump(0x88590,allocate);jump(0x88560,bounded_copy);jump(0x887f0,bounded_copy);jump(0x885a0,bounded_set);
 jump(0x88600,bounded_length);jump(0x890a0,bounded_string);jump(0x4ab90,subscription);jump(0x6dac4,singleton);jump(0x6e3b0,capture);jump(0x4aedc,send_capture);
 jump(0x89450,ssl_read_stub);jump(0x89470,ssl_write_stub);jump(0x89460,ssl_error_stub);
 aux[0]=1;ptr(0x8ed00,aux);ptr(0x8eba8,aux+8);ptr(0x8ed58,aux);ptr(0x8ee00,aux);ptr(0x8ed60,aux+0x100);ptr(0x8ee08,aux+0x200);
 *(u64 *)(aux+0x200)=(u64)(aux+0x300);*(u64 *)(aux+0x328)=(u64)nothing;
 ptr(0x8eef0,aux+0x1000);ptr(0x8eef8,aux+0x2000);
 for(u64 at=0;at<size;at+=64)__asm__ volatile("dc cvau, %0"::"r"(base+at):"memory");
 __asm__ volatile("dsb ish":::"memory");for(u64 at=0;at<size;at+=64)__asm__ volatile("ic ivau, %0"::"r"(base+at):"memory");__asm__ volatile("dsb ish\nisb":::"memory");
 require(syscall6(226,(long)base,size,1,0,0,0)==0,"image_readonly");
 const u64 pages[]={0x3f000,0x4a000,0x54000,0x55000,0x61000,0x62000,0x63000,0x69000,0x6d000,0x6e000,0x6f000,0x75000,0x7f000,0x88000,0x89000};
 for(u64 i=0;i<sizeof(pages)/sizeof(pages[0]);i++)require(syscall6(226,(long)(base+pages[i]),0x1000,5,0,0,0)==0,"code_readexecute");
}
struct filter {u16 code;u8 jt,jf;u32 k;};
struct program {u16 len;const struct filter *filter;};
#define ST(c,k) {c,0,0,k}
#define JE(k,t,f) {0x15,t,f,k}
static void restrict_process(void) {
    /* ARM64 seccomp: exit, stdout/stderr; streaming additionally reads stdin. */
    const struct filter code[]={
      ST(0x20,4),JE(0xc00000b7,1,0),ST(0x06,0x80000000),
      ST(0x20,0),JE(93,6,0),JE(94,5,0),JE(64,0,3),
      ST(0x20,16),JE(1,2,0),JE(2,1,0),ST(0x06,0x00050001),ST(0x06,0x7fff0000)
    };
    const struct filter stream_code[]={
      ST(0x20,4),JE(0xc00000b7,1,0),ST(0x06,0x80000000),
      ST(0x20,0),JE(93,9,0),JE(94,8,0),JE(64,2,0),JE(63,4,0),
      ST(0x06,0x00050001),ST(0x20,16),JE(1,3,0),JE(2,2,3),
      ST(0x20,16),JE(0,0,1),ST(0x06,0x7fff0000),ST(0x06,0x00050001)
    };
    const struct program p={stream_mode?sizeof(stream_code)/sizeof(stream_code[0]):sizeof(code)/sizeof(code[0]),stream_mode?stream_code:code};
    require(syscall6(167,38,1,0,0,0,0)==0,"no_new_privs");
    require(syscall6(167,22,2,(long)&p,0,0,0)==0,"seccomp_install");
    require(syscall6(198,2,1,0,0,0,0)==-1,"network_not_blocked");
    require(syscall6(29,-1,0,0,0,0,0)==-1,"binder_not_blocked");
    require(syscall6(56,-100,(long)"/dev/null",0,0,0,0)==-1,"files_not_blocked");
    require(syscall6(220,0,0,0,0,0,0)==-1,"process_creation_not_blocked");
    require(syscall6(63,9,(long)input,1,0,0,0)==-1,"other_read_fd_not_blocked");
}
static void initialize(void) {fill(object,0,sizeof(object));*(u32 *)(object+0x294)=0xff;((void (*)(void *))(base+0x61cf0))(object);require(subscription_bytes==176,"native_table");}
static void ingest(const u8 *value,u32 n) {require(n<=74,"input_bound");copy(input,value,n);((void (*)(void *,const void *,u32))(base+0x627d4))(object,input,n);fill(input,0xa5,n);}
static void report(u32 reply) {u32 before=captures;((void (*)(void *,u32))(base+0x55350))(object,reply);require(captures==before+1,"report_call");}
static u8 context_before[sizeof(object)];
static void number(u32 n) {char digits[16];u32 count=0;do{digits[count++]=(char)('0'+n%10);n/=10;}while(n);while(count)syscall6(64,1,(long)&digits[--count],1,0,0,0);}
static int hex_digit(char c) {if(c>='0'&&c<='9')return c-'0';if(c>='a'&&c<='f')return c-'a'+10;return -1;}
static u32 stream_buffers, stream_bytes, stream_lines;
static int source_unregistered, source_done;
static void consume_line(char *line,u32 n) {
 static const char prefix[]="[CloudCanSnapshotProbe] OPAQUE ";
 static const char unreg[]="[CloudCanSnapshotProbe] UNREGISTERED";
 static const char done[]="[CloudCanSnapshotProbe] DONE received=";
 static const char clean[]=" dropped=0 callback_errors=0 queued=0";
 if(n==sizeof(unreg)-1 && equal(line,unreg,n)){source_unregistered=1;return;}
 if(n>=sizeof(done)-1+sizeof(clean)-1 && equal(line,done,sizeof(done)-1)){
  require(equal(line+n-(sizeof(clean)-1),clean,sizeof(clean)-1),"source_unclean");source_done=1;return;
 }
 if(n<sizeof(prefix)-1 || !equal(line,prefix,sizeof(prefix)-1))return;
 u32 raw=0,stated=0;int have_size=0;
 for(u32 i=sizeof(prefix)-1;i+7<n;i++) {
  if(equal(line+i," bytes=",7)){u32 j=i+7;while(j<n && line[j]>='0' && line[j]<='9'){stated=stated*10+(line[j++]-'0');require(stated<=74,"stream_size_bound");}have_size=1;}
  if(equal(line+i," raw=",5)){raw=i+5;break;}
 }
 require(raw && have_size && stated>=18 && n-raw==stated*2,"stream_envelope");
 u8 value[74];for(u32 i=0;i<stated;i++){int hi=hex_digit(line[raw+2*i]),lo=hex_digit(line[raw+2*i+1]);require(hi>=0&&lo>=0,"stream_hex");value[i]=(u8)((hi<<4)|lo);}
 ingest(value,stated);stream_buffers++;stream_bytes+=stated;require(stream_buffers<=10000,"stream_count_bound");
}
__attribute__((noreturn)) static void stream(void) {
 initialize();char line[512];u8 chunk[1024];u32 used=0,total=0;
 for(;;){long n=syscall6(63,0,(long)chunk,sizeof(chunk),0,0,0);require(n>=0,"stdin_read");if(!n)break;total+=(u32)n;require(total<=2000000,"stream_byte_bound");
  for(long i=0;i<n;i++){if(chunk[i]=='\n'){consume_line(line,used);used=0;stream_lines++;require(stream_lines<=20000,"stream_line_bound");}else{require(used<sizeof(line),"stream_line_length");line[used++]=(char)chunk[i];}}
 }
 require(used==0 && stream_buffers>0 && source_unregistered && source_done,"stream_incomplete");report(0);require(capture_size==104 && sends==1 && last_command==512,"stream_report");
 write_text("{\"passed\":true,\"mode\":\"stream\",\"buffers\":");number(stream_buffers);write_text(",\"buffer_bytes\":");number(stream_bytes);
 write_text(",\"native_report_bytes\":104,\"network_binder_files_clone_blocked\":true,\"stdin_only_read\":true,\"cloud_upload\":false,\"daemon_started\":false}\n");
 syscall6(94,0,0,0,0,0,0);for(;;){}
}
__attribute__((noreturn)) void probe_main(u64 *initial_stack) {
 if(initial_stack[0]==2){const char *arg=(const char *)initial_stack[2];require(equal(arg,"--stream",9),"argument");stream_mode=1;}else require(initial_stack[0]==1,"arguments");
 u64 core[2]={0,0};require(syscall6(261,0,4,(long)core,0,0,0)==0,"core_limit");u64 cpu[2]={2,2};require(syscall6(261,0,0,(long)cpu,0,0,0)==0,"cpu_limit");u64 timer[4]={0,0,stream_mode?25:5,0};require(syscall6(103,0,(long)timer,0,0,0,0)==0,"wall_timer");require(syscall6(167,4,0,0,0,0,0)==0,"dumpable");
 __asm__ volatile("msr tpidr_el0, %0"::"r"(tls):"memory");prepare_mapping();restrict_process();
 if(stream_mode)stream();
 initialize();report(0);require(sends==0,"empty_cache_sent");cases++;
 copy(context_before,object,sizeof(object));((void (*)(void *,const void *,u32))(base+0x627d4))(object,0,18);
 for(u32 n=0;n<18;n++)((void (*)(void *,const void *,u32))(base+0x627d4))(object,input,n);
 require(equal(context_before,object,sizeof(object)),"short_input_changed_context");cases++;
 busy=1;ingest(BUFFERS[0],BUFFER_SIZES[0]);busy=0;require(equal(context_before,object,sizeof(object)),"busy_input_changed_context");cases++;
 for(u32 i=0;i<BUFFER_COUNT;i++)ingest(BUFFERS[i],BUFFER_SIZES[i]);report(0);
 require(capture_size==sizeof(NATIVE_EXPECTED_BODY) && equal(captured,NATIVE_EXPECTED_BODY,capture_size) && sends==1 && last_command==512,"native_replay_mismatch");copy(saved,captured,capture_size);cases++;
 report(0);require(equal(captured,saved,capture_size),"source_buffer_lifetime");cases++;
 initialize();for(u32 i=0;i<BUFFER_COUNT;i++)ingest(BUFFERS[i],BUFFER_SIZES[i]);report(0);require(equal(captured,saved,capture_size),"new_context_mismatch");cases++;
 for(u32 i=0;i<16;i++)object[0x7d8+i]=i;report(1);require(capture_size==120 && equal(captured,object+0x7d8,16) && equal(captured+16,saved,104) && last_command==511,"native_reply_mismatch");cases++;
 const u32 sizes[]={1,18,128};
 for(u32 j=0;j<3;j++){u32 n=sizes[j];for(u32 i=0;i<n;i++)input[i]=(u8)(i*37+n);copy(saved,input,n);
 ((void (*)(void *,u32,const char *,u32,const void *,u32,u32))(base+0x75bdc))(aux+0x500,512,"localhost",443,input,n,0);
 const void *owned=*(void **)(aux+0x520);require(owned!=input && equal(owned,saved,n),"queued_copy");fill(input,0xa5,n);require(equal(owned,saved,n),"queued_copy_lifetime");}cases++;
 *(u64 *)(aux+0x610)=0x123456;const int results[]={32,7,0,-1};
 for(u32 read=0;read<2;read++) {
  for(u32 j=0;j<4;j++) {
   socket_result=results[j];fill(input,0xcc,32);u32 before=ssl_calls;
   int r=((int (*)(void *,u32,void *,u32))(base+(read?0x7f760:0x7f81c)))(aux+0x600,99,input,32);
   require(r==socket_result && ssl_calls==before+1,"ssl_result");
   if(read && r>0)for(int i=0;i<r;i++)require(input[i]==i,"ssl_read_bytes");
  }
 }
 cases++;
 require(cases==9,"case_count");write_text("{\"passed\":true,\"cases\":9,\"original_arm64_executed\":true,\"raw_callbacks_replayed\":true,\"network_binder_files_clone_blocked\":true,\"daemon_started\":false}\n");
 syscall6(94,0,0,0,0,0,0);for(;;){}
}
__asm__(".global _start\n_start:\n mov x0, sp\n bl probe_main\n brk #0\n");
