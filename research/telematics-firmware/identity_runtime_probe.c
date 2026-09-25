/* Isolated ARM64 execution of reviewed stock identity routines.
 * Synthetic inputs only. No Binder, crypto, property API, network or daemon startup.
 * Build with build_identity_runtime.py; this is not an installed cloud adapter.
 */
typedef unsigned long u64;
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;
extern const u8 identity_image_start[], identity_image_end[];
#include "identity_runtime_expected.h"
static u8 *base;
static u8 object[0x108] __attribute__((aligned(16)));
static u8 helper[16], packet211[128], packet220[128], tls[128];
static u8 body[64];
static u32 command, body_size, property_reads, cases;
static const char *override_imsi = IMSI_A;
static int use_override = 1;

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
static void *allocate(u64 n) { require(n==16,"allocation_boundary"); fill(helper,0,16); return helper; }
static void *bounded_copy(void *d,const void *s,u64 n) { require(n<=4096,"copy_boundary"); copy(d,s,n);return d; }
static void *bounded_set(void *d,int b,u64 n) { require(n<=4096,"set_boundary");fill(d,b,n);return d; }
static int source(const char *key,char *out,const char *unused) {
    require(unused==0,"property_default");
    u64 caller=(u64)__builtin_return_address(0)-(u64)base;
    const char *v;
    if(caller==0x6dd90 && equal(key,"ril.csim.iccid",14)) v=use_override?ICCID_A:ICCID_B;
    else if(caller==0x6deb4 && equal(key,"ril.imsi",9)) v=use_override?override_imsi:IMSI_B;
    else if(caller==0x6de40 && equal(key,"debug.ro.serialno",18)) v="";
    else { fail("unreviewed_property_call"); }
    property_reads++; copy(out,v,length(v)+1); return length(v);
}
static int nonce(u8 *out,u32 n) { require(n==16,"nonce_size");for(u32 i=0;i<16;i++)out[i]=32+i;return 0; }
static int capture(void *self,void *unused,void *packet,u32 cmd,u32 flag,const u8 *value,u32 n,u32 tail) {
    (void)self;(void)unused;(void)packet;(void)flag;(void)tail;
    require((cmd==211 && n==35)||(cmd==220 && n==48),"serializer_boundary");
    command=cmd;body_size=n;copy(body,value,n);return 1;
}
static void jump(u64 at,void *target) {
    /* LDR x16, PC+8; BR x16; absolute address. Only this private mapping changes. */
    *(u32 *)(base+at)=0x58000050;*(u32 *)(base+at+4)=0xd61f0200;
    *(u64 *)(base+at+8)=(u64)target;
}
static void protect(u64 at,u64 size,int prot) {
    require(syscall6(226,(long)(base+at),size,prot,0,0,0)==0,"mprotect");
}
static void prepare_mapping(void) {
    u64 size=identity_image_end-identity_image_start;
    require(size==0x98000,"image_size");
    long mapping=syscall6(222,0,size,3,0x22,-1,0);
    require((u64)mapping<(u64)-4095,"mmap");base=(u8 *)mapping;
    copy(base,identity_image_start,size);
    const u64 noops[]={0x88990,0x7c920,0x88410,0x883f0,0x883c0};
    for(u64 i=0;i<sizeof(noops)/sizeof(noops[0]);i++)jump(noops[i],nothing);
    jump(0x88470,allocate);jump(0x88640,source);
    jump(0x88560,bounded_copy);jump(0x887f0,bounded_copy);jump(0x885a0,bounded_set);
    jump(0x699a4,nonce);jump(0x6e3b0,capture);
    /* Return through each original epilogue immediately after capturing the body. */
    *(u32 *)(base+0x6e10c)=0x17ffffeb; /* B 0x6e0b8 */
    *(u32 *)(base+0x6f0b4)=0x14000005; /* B 0x6f0c8 */
    *(u64 *)(base+0x8eec8)=(u64)packet211;
    *(u64 *)(base+0x8eed0)=(u64)packet220;
    /* ARM instruction cache synchronization; no executable writable mapping. */
    for(u64 at=0;at<size;at+=64)__asm__ volatile("dc cvau, %0"::"r"(base+at):"memory");
    __asm__ volatile("dsb ish":::"memory");
    for(u64 at=0;at<size;at+=64)__asm__ volatile("ic ivau, %0"::"r"(base+at):"memory");
    __asm__ volatile("dsb ish\nisb":::"memory");
    protect(0,size,1);
    const u64 pages[]={0x69000,0x6d000,0x6e000,0x6f000,0x7c000,0x7d000,0x88000};
    for(u64 i=0;i<sizeof(pages)/sizeof(pages[0]);i++)protect(pages[i],0x1000,5);
}
struct filter {u16 code;u8 jt,jf;u32 k;};
struct program {u16 len;const struct filter *filter;};
#define ST(c,k) {c,0,0,k}
#define JE(k,t,f) {0x15,t,f,k}
static void restrict_process(void) {
    /* ARM64 seccomp: only exit and writes to stdout/stderr. Everything else EPERM. */
    const struct filter code[]={
      ST(0x20,4),JE(0xc00000b7,1,0),ST(0x06,0x80000000),
      ST(0x20,0),JE(93,6,0),JE(94,5,0),JE(64,0,3),
      ST(0x20,16),JE(1,2,0),JE(2,1,0),ST(0x06,0x00050001),ST(0x06,0x7fff0000)
    };
    const struct program p={sizeof(code)/sizeof(code[0]),code};
    require(syscall6(167,38,1,0,0,0,0)==0,"no_new_privs");
    require(syscall6(167,22,2,(long)&p,0,0,0)==0,"seccomp_install");
    require(syscall6(198,2,1,0,0,0,0)==-1,"network_not_blocked");
    require(syscall6(29,-1,0,0,0,0,0)==-1,"binder_not_blocked");
    require(syscall6(56,-100,(long)"/dev/null",0,0,0,0)==-1,"files_not_blocked");
    require(syscall6(220,0,0,0,0,0,0)==-1,"process_creation_not_blocked");
}
static void call(u64 at) { ((void (*)(void *))(base+at))(object); }
static void construct(void) {
    fill(object,0xcc,sizeof(object));call(0x6d9f4);
    u8 zero[20]={0}; require(equal(object+0x64,zero,20)&&equal(object+0x79,zero,15)&&equal(object+0xa7,zero,16),"constructor");
}
static void check(const char *imsi,const char *iccid,const u8 *digest) {
    require(((int (*)(void *))(base+0x6dcf0))(object)==1,"prepare");
    require(equal(object+0xa7,digest,16),"native_digest");
    command=0;call(0x6e00c);
    require(command==211 && body_size==35 && equal(body,imsi,15)&&equal(body+15,iccid,20),"registration_body");
    command=0;call(0x6f01c);
    u8 expected[48]={0};for(u32 i=0;i<16;i++)expected[i]=32+i;copy(expected+32,digest,16);
    require(command==220 && body_size==48 && equal(body,expected,48),"login_body");cases++;
}
__attribute__((noreturn)) void probe_main(void) {
    /* Bound CPU/wall time and disable core dumps before mapping any stock code. */
    u64 core_limit[2]={0,0};require(syscall6(261,0,4,(long)core_limit,0,0,0)==0,"core_limit");
    u64 cpu_limit[2]={2,2};require(syscall6(261,0,0,(long)cpu_limit,0,0,0)==0,"cpu_limit");
    u64 timer[4]={0,0,5,0};require(syscall6(103,0,(long)timer,0,0,0,0)==0,"wall_timer");
    require(syscall6(167,4,0,0,0,0,0)==0,"dumpable");
    __asm__ volatile("msr tpidr_el0, %0"::"r"(tls):"memory");
    prepare_mapping();restrict_process();
    construct();check(IMSI_A,ICCID_A,DIGEST_A);check(IMSI_A,ICCID_A,DIGEST_A);
    use_override=0;check(IMSI_A,ICCID_A,DIGEST_A);
    construct();check(IMSI_B,ICCID_B,DIGEST_B);
    use_override=1;override_imsi=IMSI_ZERO;construct();check(IMSI_ZERO,ICCID_A,DIGEST_ZERO);
    u32 before=property_reads;check(IMSI_ZERO,ICCID_A,DIGEST_ZERO);require(property_reads>before,"zero_digest_reread");
    override_imsi=IMSI_A;construct();check(IMSI_A,ICCID_A,DIGEST_A);
    require(cases==7,"case_count");
    write_text("{\"passed\":true,\"cases\":7,\"synthetic_only\":true,\"original_arm64_executed\":true,\"network_binder_files_clone_blocked\":true,\"daemon_started\":false}\n");
    syscall6(94,0,0,0,0,0,0);for(;;){}
}
__asm__(".global _start\n_start:\n bl probe_main\n brk #0\n");
