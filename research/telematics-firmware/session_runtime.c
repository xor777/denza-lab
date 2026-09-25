/* Original-code worker. Private mapping, bounded IPC, no socket/Binder/file calls. */
typedef unsigned long u64;
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;
extern const u8 native_image_start[], native_image_end[];
#include "session_pages.h"
#include "bounded_arena.h"
static u8 *base;
static u8 object[0x5000] __attribute__((aligned(16)));
static u8 aux[0xc000] __attribute__((aligned(16)));
static u8 arena[0x10000] __attribute__((aligned(16)));
static u8 input[1024], tls[128], vin[18];
static struct bounded_arena heap;
static u8 framed[1024], decoded[1024], body[256];
static char iccid[21],imsi[16],serial[92],domain[256];
static u8 key[16],uuid[16],random_nonce[16];
static u32 timestamp,body_size,port,expected_command,prepared,callbacks;

static u32 sends, last_command, subscription_bytes, frames, frame_size;
static u32 decodes, decoded_size, decoded_command;
#ifdef CONTROL_EXPERIMENT
/* Supplied fresh, read-only SDK scalars. Never defaults for missing live data. */
static u32 env[8], env_ready, control_replies, control_writes, terminal;
static u8 effects[8][1024];
static u32 effect_len[8], effect_fid[8], effect_count;
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
__attribute__((noreturn)) static void fail(const char *why){text("{\"passed\":false,\"stage\":\"");text(why);text("\"}\n");syscall6(94,1,0,0,0,0,0);for(;;){}}
static void require(int ok,const char *why){if(!ok)fail(why);}
static long nothing(void){return 0;}
static void *allocate(u64 n){enum arena_status status;void *p=arena_allocate(&heap,n,&status);require(status==ARENA_OK,"allocation_bound");return p;}
static void deallocate(void *p){require(arena_deallocate(&heap,p)==ARENA_OK,"deallocation_bound");}
static void *bounded_copy(void *d,const void *s,u64 n){require(n<=4096,"copy_bound");copy(d,s,n);return d;}
static void *checked_copy(void *d,const void *s,u64 n,u64 bound){require(n<=bound,"checked_copy_bound");return bounded_copy(d,s,n);}
static void *bounded_set(void *d,int b,u64 n){require(n<=4096,"set_bound");fill(d,b,n);return d;}
static u64 bounded_length(const char *s){u64 n=0;while(n<128 && s[n])n++;require(n<128,"string_bound");return n;}
static int bounded_compare(const u8 *a,const u8 *b,u64 n){require(n<=17,"compare_bound");for(u64 i=0;i<n;i++){if(a[i]!=b[i])return (int)a[i]-b[i];if(!a[i])break;}return 0;}
static int property(const char *name,char *out,const char *fallback){
 (void)fallback;const char *value=0;
 if(equal(name,"persist.sys.byd.apn_type",24))value="double_apn";
 else if(equal(name,"ril.csim.iccid",14))value=iccid;
 else if(equal(name,"ril.imsi",9))value=imsi;
 else if(equal(name,"debug.ro.serialno",18))value=serial;
 require(value!=0,"property_boundary");u64 n=bounded_length(value);copy(out,value,n+1);return n;
}
static long current_time(void){return timestamp;}
static int nonce(u8 *out,u32 n){require(n==16,"nonce_bound");copy(out,random_nonce,16);return 0;}
static void *string_copy(char *out,const char *in){u64 n=bounded_length(in);copy(out,in,n+1);return out;}
static int memory_compare(const u8 *a,const u8 *b,u64 n){require(n<=128,"memcmp_bound");for(u64 i=0;i<n;i++)if(a[i]!=b[i])return (int)a[i]-b[i];return 0;}
static int endpoint(void *self,const char *host,u32 cmd){require(self==object && cmd==220,"endpoint_boundary");u64 n=bounded_length(host);copy(domain,host,n+1);return 1;}
static void endpoint_port(void *self,void *address,u32 p){(void)address;require(self==aux+0xb000 && p>0 && p<65536,"port_boundary");port=p;}
#ifdef CONTROL_EXPERIMENT
static void effect(u32 fid,const void *value,u32 n){require(effect_count<8 && n>0 && n<=1024,"effect_bound");copy(effects[effect_count],value,n);effect_len[effect_count]=n;effect_fid[effect_count++]=fid;}
#endif
static void subscription(void *self,u32 device,u32 fid,const void *value,u32 n){
#ifdef CONTROL_EXPERIMENT
 if(fid==0xaa000004 || fid==0xaa00001e){require(env_ready && self==object && device==1034 && value && n<=256,"control_write_boundary");effect(fid,value,n);control_writes++;return;}
#endif
 require(self==object && device==1034 && fid==0xaa000023 && value && n==176,"subscription_boundary");subscription_bytes=n;
}
__attribute__((naked)) static void singleton(void){__asm__("adrp x9, aux\nadd x9, x9, :lo12:aux\nadd x9, x9, #0x400\nstr x9, [x8]\nret");}
static void send_capture(void *self,u32 cmd){
#ifdef CONTROL_EXPERIMENT
 if(cmd==532 || cmd==536 || cmd==0x2154){require(self==object && frame_size>0,"control_send_boundary");effect(0,framed,frame_size);return;}
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
 require(type==3 && source==0,"decoder_source");
 u32 ok=((u32 (*)(void *,u32,u32,const void *,u32,u16 *,u8 *,u8 *,u8 *,u16 *))(base+0x98040))(helper,type,source,in,n,cmd,flag,version,out,size);
 if(ok&1){
#ifdef CONTROL_EXPERIMENT
  if(!expected_command){text("META decoded command=");number(*cmd);text(" flag=");number(*flag);text(" version=");number(*version);text(" size=");number(*size);text("\n");}
  if(!expected_command){require(*cmd==511||*cmd==532||*cmd==536,"control_command_boundary");
   if(*cmd==532)require(env_ready && *version==0 && (*flag==254||*flag==4) && *size>=21 && *size<=65 && out[17]==3,"climate_only_boundary");
   if(*cmd==536)require(env_ready && *flag==254 && *version==0 && (*size==1 || (*size==2 && out[1]==1)),"wake_request_boundary");
  }else{require(*cmd==expected_command,"command_boundary");}
#else
  require(*cmd==expected_command,"command_boundary");
#endif
  if(*cmd==511)require(*flag==254 && *version<=1 && *size==17,"status_request_boundary");require(*size<=sizeof(decoded),"decoded_bound");copy(decoded,out,*size);decoded_size=*size;decoded_command=*cmd;decodes++;
 }
 return ok;
}
static void body_capture(void *helper,void *unused,void *packet,u32 cmd,u32 flag,const void *value,u32 n,u32 version){
 require((cmd==211 && n==35)||(cmd==200 && n==2)||(cmd==220 && n==48)||(cmd==511 && n==120)
#ifdef CONTROL_EXPERIMENT
 ||(cmd==532 && (n==16||(n>=20&&n<=64)))||(cmd==536 && n==1)
#endif
 ,"body_boundary");
 copy(body,value,n);body_size=n;
 ((void (*)(void *,void *,void *,u32,u32,const void *,u32,u32))(base+0x98080))(helper,unused,packet,cmd,flag,value,n,version);
}
static void jump(u64 at,void *target){*(u32 *)(base+at)=0x58000050;*(u32 *)(base+at+4)=0xd61f0200;*(u64 *)(base+at+8)=(u64)target;}
static void ptr(u64 at,void *target){*(u64 *)(base+at)=(u64)target;}
static void thunk(u64 entry,u64 at,void *wrapper){copy(base+at,base+entry,16);jump(at+16,base+entry+16);jump(entry,wrapper);}
#ifdef CONTROL_EXPERIMENT
static int control_property(void *unused,const char *name,int fallback){(void)unused;(void)fallback;require(env_ready && equal(name,"persist.sys.repair_mode.enable",30),"control_property_boundary");return env[5];}
static int get_int(void *self,u32 device,u32 fid,u32 *out){(void)self;require(env_ready && out,"getter_environment");
 if(device==1014&&fid==0x14400008)*out=env[2];else if(device==1023&&fid==0x2f4000fa)*out=env[3];else if(device==1001&&fid==0x12d0002a)*out=env[0];else fail("control_integer_getter");return 0;}
static int get_float(void *self,u32 device,u32 fid,u32 *out){(void)self;require(env_ready&&device==1014&&fid==0x4a505038&&out,"control_float_getter");*out=env[4];return 0;}
static void timer_capture(void *self,void *slot,u32 once,u32 seconds,void *callback,void *context){(void)callback;require(self==object&&slot==object+0x5d8&&once==0&&seconds==16&&context==object,"control_timer_boundary");}
static int format_zero(char *out,u32 cap,const char *format,u32 value){require(cap>=2&&equal(format,"%d",3)&&value==0,"control_format_boundary");copy(out,"0",2);return 1;}
static int control_property_set(const char *name,const char *value){require(equal(name,"sys.cloud.remote_controling",27)&&equal(value,"0",2),"control_property_write_boundary");return 0;}
static void result_send(void *self,u32 signal){require(self==aux+0xb000&&signal==1&&frame_size>0,"result_send_boundary");effect(0,framed,frame_size);control_replies++;}
static void result_journal(void *self,u32 command,u32 flag,u32 ikey){(void)self;require(command==3&&(flag==1||flag==2)&&ikey<=1,"climate_result_boundary");terminal=flag;}
#endif
static void prepare(void){
 require(native_image_end-native_image_start==0x98000,"image_size");long mapping=syscall6(222,0,0x99000,3,0x22,-1,0);require((u64)mapping<(u64)-4095,"mmap");base=(u8 *)mapping;copy(base,native_image_start,0x98000);
 const u64 noops[]={0x883c0,0x883f0,0x6ecf8,0x54320,0x888b0,0x88dd0,0x3f7f4,0x7e934,0x7e97c,0x88990,0x7c920,0x88410,0x39288,0x48018,0x480c4,0x4b3e0,0x5d238,0x55b30,0x48280,0x75904,0x54964,0x72718};
 for(u64 i=0;i<sizeof(noops)/sizeof(noops[0]);i++)jump(noops[i],nothing);
 jump(0x88470,allocate);jump(0x88590,allocate);jump(0x88460,deallocate);jump(0x885b0,deallocate);jump(0x88570,bounded_copy);jump(0x88960,memory_compare);jump(0x88600,bounded_length);jump(0x890a0,string_copy);jump(0x699a4,nonce);jump(0x52ea0,endpoint);jump(0x75b70,endpoint_port);jump(0x88560,bounded_copy);jump(0x887f0,checked_copy);jump(0x885a0,bounded_set);jump(0x88620,bounded_length);jump(0x89010,bounded_compare);
 jump(0x88640,property);jump(0x88f60,current_time);jump(0x4ab90,subscription);jump(0x6dac4,singleton);jump(0x4aedc,send_capture);
 thunk(0x6e858,0x98000,encode_capture);thunk(0x727bc,0x98040,decode_capture);thunk(0x6e3b0,0x98080,body_capture);
#ifdef CONTROL_EXPERIMENT
 jump(0x48698,control_property);jump(0x88880,get_int);jump(0x88cc0,get_float);jump(0x4a41c,timer_capture);
 jump(0x88890,nothing);jump(0x71888,nothing);jump(0x4ad30,format_zero);jump(0x885c0,control_property_set);jump(0x739a0,result_send);jump(0x54e20,result_journal);
 ptr(0x8ef28,aux+0x3000);ptr(0x8ef30,aux+0x4000);ptr(0x8ebf8,aux+0x5000);ptr(0x8ef40,aux+0x6800);
#endif
 aux[0]=1;ptr(0x8ed00,aux);ptr(0x8eba8,aux+8);ptr(0x8ed58,aux);ptr(0x8ee00,aux);ptr(0x8ecc8,aux);ptr(0x8ec00,vin);
 ptr(0x8ed60,aux+0x100);ptr(0x8ee08,aux+0x200);*(u64 *)(aux+0x200)=(u64)(aux+0x300);*(u64 *)(aux+0x328)=(u64)nothing;
 ptr(0x8ed18,aux+0x700); /* Explicit empty auxiliary subscription vector. */
 ptr(0x8eec8,aux+0x6000);ptr(0x8eda8,aux+0x8000);ptr(0x8eed0,aux+0x9000);ptr(0x8ecc0,aux+0xa000);
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
 const struct filter code[]={ST(0x20,4),JE(0xc00000b7,1,0),ST(0x06,0x80000000),ST(0x20,0),JE(93,9,0),JE(94,8,0),JE(64,2,0),JE(63,4,0),ST(0x06,0x00050001),ST(0x20,16),JE(1,3,0),JE(2,2,3),ST(0x20,16),JE(0,0,1),ST(0x06,0x7fff0000),ST(0x06,0x00050001)};
 const struct program p={sizeof(code)/sizeof(code[0]),code};require(syscall6(167,38,1,0,0,0,0)==0,"no_new_privs");require(syscall6(167,22,2,(long)&p,0,0,0)==0,"seccomp");
 require(syscall6(198,2,1,0,0,0,0)==-1 && syscall6(29,-1,0,0,0,0,0)==-1 && syscall6(56,-100,(long)"/dev/null",0,0,0,0)==-1 && syscall6(220,0,0,0,0,0,0)==-1,"isolation");
}
static int digit(char c){if(c>='0' && c<='9')return c-'0';if(c>='a' && c<='f')return c-'a'+10;return -1;}
static u32 unhex(const char *s,u8 *out,u32 cap){u64 n=length(s);require(n%2==0 && n/2<=cap,"hex_bound");for(u32 i=0;i<n/2;i++){int a=digit(s[2*i]),b=digit(s[2*i+1]);require(a>=0 && b>=0,"hex_digit");out[i]=(a<<4)|b;}return n/2;}
static void hex(const u8 *in,u32 n){static const char ds[]="0123456789abcdef";char line[2049];require(n<=1024,"hex_output");for(u32 i=0;i<n;i++){line[2*i]=ds[in[i]>>4];line[2*i+1]=ds[in[i]&15];}line[2*n]=0;text(line);}
static void number(u32 n){char s[16];u32 at=15;s[at]=0;do{s[--at]='0'+n%10;n/=10;}while(n);text(s+at);}
#ifdef CONTROL_EXPERIMENT
static void drain_effects(void){for(u32 i=0;i<effect_count;i++){text(effect_fid[i]?"AUTO ":"NET ");if(effect_fid[i]){number(effect_fid[i]);text(" ");}hex(effects[i],effect_len[i]);text("\n");}effect_count=0;}
static void command_result(void){drain_effects();text("CONTROL ");number(decoded_command);text(" ");number(control_replies);text(" ");number(terminal);text("\n");}
#endif
static int line(char *out,u32 cap){u32 n=0;for(;;){char c;long got=syscall6(63,0,(long)&c,1,0,0,0);if(!got)return 0;require(got==1,"stdin");if(c=='\n'){out[n]=0;return 1;}require(n+1<cap && c>=32 && c<=126,"line_bound");out[n++]=c;}}
static void receive(u32 command,const char *arg){u32 n=unhex(arg,input,sizeof(input));require(n>=53,"frame_short");expected_command=command;decodes=0;sends=0;frames=0;
#ifdef CONTROL_EXPERIMENT
 /* Completion is an event from this exchange, not a level carried from a
  * previous command. Original native busy/correlation state is untouched. */
 terminal=0;
#endif
 ((void (*)(void *,u32,const void *,u32))(base+0x573ec))(object,0,input,n);
 require(decodes==1 && (!command || decoded_command==command),"native_decode_rejected");
#ifdef CONTROL_EXPERIMENT
 if(!command && decoded_command!=511){command_result();return;}
 if(!command)command=decoded_command;
#endif
 if(command==200){require(port && domain[0] && sends==1 && last_command==220,"discovery_failed");text("ENDPOINT ");text(domain);text(" ");number(port);text("\n");}
 else if(command==220){text("LOGIN ");number(object[0x2d0]);text("\n");}
 else if(command==211){text("REG ");number(object[0x35c]);text("\n");}
 else {require(command==511 && callbacks>0 && sends==1 && frames==1 && body_size==120,"status_failed");text("STATUS ");number((u32)body[100]|((u32)(body[101]&15)<<8));text(" ");hex(framed,frame_size);text("\n");}
}
__attribute__((noreturn)) void probe_main(void){
 u64 core[2]={0,0},cpu[2]={10,10},timer[4]={0,0,180,0};require(syscall6(261,0,4,(long)core,0,0,0)==0,"core_limit");require(syscall6(261,0,0,(long)cpu,0,0,0)==0,"cpu_limit");require(syscall6(103,0,(long)timer,0,0,0,0)==0,"wall_timer");require(syscall6(167,4,0,0,0,0,0)==0,"dumpable");
 __asm__ volatile("msr tpidr_el0, %0"::"r"(tls):"memory");arena_init(&heap,arena,sizeof(arena));prepare();restrict_process();text("READY\n");char cmd[2100];u32 mask=0,operations=0;
 while(line(cmd,sizeof(cmd))){require(++operations<=15000,"operation_budget");char *arg=cmd;while(*arg && *arg!=' ')arg++;if(*arg)*arg++=0;
  if(equal(cmd,"QUIT",5))break;
#ifdef CONTROL_EXPERIMENT
  if(equal(cmd,"RX",3)){require(prepared&&env_ready,"control_not_ready");receive(0,arg);continue;}
  if(equal(cmd,"MCU",4)){require(prepared&&env_ready,"mcu_not_ready");u32 n=unhex(arg,input,sizeof(input));
   int wake=n==7&&input[1]==2&&input[2]==24&&input[5]==1;
   require(wake||(n>=26&&n<=70&&input[1]==2&&input[2]==20&&input[22]==3),"mcu_climate_boundary");
   require(input[4]==1||input[4]==2||input[4]==3,"mcu_flag_boundary");
   if(!wake)require(equal(input+6,object+0x422e,16),"mcu_correlation_boundary");
   terminal=0; /* A wake/intermediate callback cannot inherit prior success. */
   /* An interleaved cloud status/wake can change decoded_command while a
    * control is pending. Label this already-bounded MCU envelope itself. */
   decoded_command=wake?536:532;
   ((void (*)(void *,u32,const void *,u32))(base+0x6039c))(object,0x99000004,input,n);command_result();continue;}
#endif
  if(equal(cmd,"START",6)){require(mask==255 && !prepared,"init_inputs");
   ((void (*)(void *))(base+0x6d9f4))(aux+0x400);copy(aux+0x400+0xe2,uuid,16);copy(aux+0x400+0xf2,key,16);
   *(u64 *)(object+0x2b0)=(u64)(aux+0xb000);object[0x615]=1;*(u32 *)(object+0x294)=0xff;
   ((void (*)(void *))(base+0x61cf0))(object);require(subscription_bytes==176,"table");
   require(((u32 (*)(void *))(base+0x6dcf0))(aux+0x400)==1 && ((u32 (*)(void *))(base+0x72e0c))(aux+0x400)==1,"native_identity");prepared=1;text("OK\n");continue;
  }
  if(cmd[1]==0){u32 n=0;
#ifdef CONTROL_EXPERIMENT
   if(cmd[0]=='E'){require(unhex(arg,(u8 *)env,sizeof(env))==sizeof(env),"environment_input");
    require(env[0]==2&&env[1]==1&&env[2]<2&&env[3]<=1&&env[5]==0&&env[6]<=2&&env[7]==1,"awake_parked_environment");
    *(u32 *)(object+0x28c)=env[0];*(u32 *)(object+0x284)=env[1];*(u32 *)(object+0x290)=env[6];env_ready=1;text("OK\n");continue;}
#endif
   if(cmd[0]=='N'){require(unhex(arg,random_nonce,16)==16,"nonce_input");mask|=128;}
   else if(cmd[0]=='T'){require(unhex(arg,(u8 *)&timestamp,4)==4 && timestamp>0,"timestamp");mask|=64;}
   else if(cmd[0]=='H'){u32 value=0;require(unhex(arg,(u8 *)&value,4)==4 && value<=255,"charging");*(u32 *)(object+0x294)=value;}
   else if(cmd[0]=='I'){require(prepared && (n=unhex(arg,input,74))>=18,"callback_input");((void (*)(void *,const void *,u32))(base+0x627d4))(object,input,n);callbacks++;}
   else if(cmd[0]=='D'||cmd[0]=='L'||cmd[0]=='G'){require(prepared,"not_prepared");frames=0;((void (*)(void *))(base+(cmd[0]=='D'?0x6edac:cmd[0]=='L'?0x6f01c:0x6e00c)))(aux+0x400);require(frames==1,"producer");text("WIRE ");hex(framed,frame_size);text("\n");continue;}
   else {require(!prepared,"identity_frozen");
    if(cmd[0]=='V'){require(unhex(arg,vin,17)==17,"vin");mask|=1;}
    else if(cmd[0]=='K'){require(unhex(arg,key,16)==16,"key");mask|=2;}
    else if(cmd[0]=='U'){require(unhex(arg,uuid,16)==16,"uuid");mask|=4;}
    else if(cmd[0]=='C'){require(unhex(arg,(u8 *)iccid,20)==20,"iccid");for(u32 i=0;i<20;i++)require(iccid[i]>='0'&&iccid[i]<='9',"iccid_digits");mask|=8;}
    else if(cmd[0]=='M'){require(unhex(arg,(u8 *)imsi,15)==15,"imsi");for(u32 i=0;i<15;i++)require(imsi[i]>='0'&&imsi[i]<='9',"imsi_digits");mask|=16;}
    else if(cmd[0]=='S'){n=unhex(arg,(u8 *)serial,91);require(bounded_length(serial)==n,"serial_nul");mask|=32;}
    else fail("unknown_input");
   }
   text("OK\n");continue;
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
