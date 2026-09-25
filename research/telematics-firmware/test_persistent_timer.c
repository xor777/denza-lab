#include <assert.h>
#include "persistent_timer.h"
int main(void){
 struct pt_scheduler s={0};pt_u64 h=0,c=0,x=0,v=0;
 assert(pt_create(&s,0,0x5eef0,0x1000,&h)==0);
 assert(pt_arm(&s,h,16000,0)==0);
 assert(pt_advance(&s,15999)==0 && !pt_due(&s,0,&v,&c,&x));
 assert(pt_advance(&s,16000)==0 && pt_due(&s,0,&v,&c,&x));
 assert(v==h && c==0x5eef0 && x==0x1000);
 assert(!pt_due(&s,0,&v,&c,&x));
 assert(pt_delete(&s,h)==0 && pt_delete(&s,h)==-1);
 pt_u64 old=h;
 assert(pt_create(&s,0,0x5eef0,0x1000,&h)==0 && h!=old);
 assert(pt_arm(&s,h,32000,0)==0);
 assert(pt_advance(&s,48000)==0 && pt_due(&s,0,&v,&c,&x));
 assert(pt_advance(&s,47999)==-1);
 assert(pt_create(&s,1,0x4a480,0x1000,&h)==0);
 assert(pt_arm(&s,h,10000,0)==0);
 assert(pt_delete(&s,h)==0);
 assert(pt_advance(&s,58000)==0 && !pt_due(&s,1,&v,&c,&x));
 assert(pt_create(&s,1,0x4a480,0x1000,&h)==0);
 assert(pt_arm(&s,h,10000,0)==0);
 assert(pt_advance(&s,68000)==0 && pt_due(&s,1,&v,&c,&x));
 assert(c==0x4a480 && !pt_due(&s,1,&v,&c,&x));
 assert(pt_create(&s,4,0x45ca4,0x2000,&h)==0);
 pt_u64 canceled=h;
 assert(pt_arm(&s,h,5000,0)==0);
 assert(pt_delete(&s,h)==0);
 assert(pt_advance(&s,73000)==0 && !pt_due(&s,4,&v,&c,&x));
 assert(pt_create(&s,4,0x45ca4,0x2000,&h)==0 && h!=canceled);
 assert(pt_arm(&s,h,5000,0)==0);
 assert(pt_advance(&s,78000)==0 && pt_due(&s,4,&v,&c,&x));
 assert(v==h && c==0x45ca4 && x==0x2000 && !pt_due(&s,4,&v,&c,&x));
 assert(pt_delete(&s,h)==0);
 return 0;
}
