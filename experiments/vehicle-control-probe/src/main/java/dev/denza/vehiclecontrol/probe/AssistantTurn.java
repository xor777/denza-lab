package dev.denza.vehiclecontrol.probe;

/** Generation invalidates old recognition, confirmation and pending controller work. */
final class AssistantTurn {
    enum Stage { IDLE, LISTENING, REVIEW, EXECUTING, RESULT, TIMEOUT }
    record State(long id,Stage stage,String seat,String text,String message,long deadline) {
        boolean listening(){return stage==Stage.LISTENING;}
        boolean visible(){return stage!=Stage.IDLE;}
    }
    private State value=new State(0,Stage.IDLE,"","","",0);
    synchronized State state(){return value;}
    synchronized long arm(String seat,long now){long id=value.id()+1;value=new State(id,Stage.LISTENING,seat,"","",now+10_000);return id;}
    synchronized boolean current(long id){return value.id()==id&&value.visible()&&value.stage()!=Stage.TIMEOUT;}
    synchronized boolean listening(long id){return current(id)&&value.listening();}
    synchronized boolean executing(long id){return current(id)&&value.stage()==Stage.EXECUTING;}
    synchronized void partial(long id,String text){if(listening(id))value=new State(id,value.stage(),value.seat(),text,value.message(),value.deadline());}
    synchronized boolean finish(long id,String text,long now){if(!listening(id))return false;value=new State(id,Stage.RESULT,value.seat(),text,"",now+6_000);return true;}
    synchronized boolean review(long id,String message,long now){if(!current(id)||value.stage()!=Stage.RESULT)return false;value=new State(id,Stage.REVIEW,value.seat(),value.text(),message,now+30_000);return true;}
    synchronized boolean beginExecution(long id,String message){if(!current(id)||(value.stage()!=Stage.RESULT&&value.stage()!=Stage.REVIEW))return false;value=new State(id,Stage.EXECUTING,value.seat(),value.text(),message,Long.MAX_VALUE);return true;}
    synchronized void report(long id,String message,long now){if(current(id))value=new State(id,Stage.RESULT,value.seat(),value.text(),message,now+8_000);}
    synchronized void tick(long now){
        if((value.listening()||value.stage()==Stage.REVIEW)&&now>=value.deadline())value=new State(value.id(),Stage.TIMEOUT,value.seat(),value.text(),value.stage()==Stage.REVIEW?"Подтверждение не получено; ничего не отправлено":"",now+4_000);
        else if(value.visible()&&!value.listening()&&value.stage()!=Stage.EXECUTING&&now>=value.deadline())cancel();
    }
    synchronized void cancel(){value=new State(value.id()+1,Stage.IDLE,"","","",0);}
}
