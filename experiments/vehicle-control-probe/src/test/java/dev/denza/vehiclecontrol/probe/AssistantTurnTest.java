package dev.denza.vehiclecontrol.probe;
import org.junit.Test;
import static org.junit.Assert.*;

public class AssistantTurnTest {
    @Test public void approvalExpiresAndCannotExecuteAnOldOrAlreadyExecutingTurn(){
        AssistantTurn t=new AssistantTurn();long id=t.arm("Водитель",0);t.finish(id,"включи климат",10);
        assertTrue(t.review(id,"Проверьте план",20));t.tick(30_020);assertFalse(t.beginExecution(id,"позднее подтверждение"));
        long next=t.arm("Пассажир",31_000);t.finish(next,"открой окно",31_100);
        assertFalse(t.beginExecution(id,"старый план"));assertTrue(t.beginExecution(next,"новый план"));
        assertFalse(t.beginExecution(next,"повторный клик"));t.tick(60_000);assertTrue(t.executing(next));
        t.cancel();t.report(next,"запоздалый ответ",61_000);assertFalse(t.state().visible());
    }
    @Test public void wakePartialFinalAndDismissAreSeparateStates(){
        AssistantTurn t=new AssistantTurn();assertFalse(t.state().visible());long id=t.arm("Водитель",100);
        assertTrue(t.state().listening());assertEquals("",t.state().text());t.partial(id,"покажи");assertEquals("покажи",t.state().text());
        assertTrue(t.finish(id,"покажи текущее время",500));assertEquals(AssistantTurn.Stage.RESULT,t.state().stage());
        t.partial(id,"устаревшее");assertEquals("покажи текущее время",t.state().text());
        t.tick(6499);assertTrue(t.state().visible());t.tick(6500);assertFalse(t.state().visible());
    }
    @Test public void cancelAndNewWakeInvalidatePreviousResults(){
        AssistantTurn t=new AssistantTurn();long old=t.arm("Водитель",0);t.partial(old,"старое");t.cancel();
        assertFalse(t.finish(old,"поздний результат",20));long next=t.arm("Пассажир",30);
        t.partial(old,"задержанный текст");assertEquals("",t.state().text());assertFalse(t.finish(old,"старое",40));
        assertTrue(t.finish(next,"новое",50));assertEquals("Пассажир",t.state().seat());
    }
    @Test public void timeoutRetainsPartialOnlyAsTimeoutAndCannotBecomeFinal(){
        AssistantTurn t=new AssistantTurn();long id=t.arm("Водитель",0);t.partial(id,"незавершённая фраза");t.tick(10_000);
        assertEquals(AssistantTurn.Stage.TIMEOUT,t.state().stage());assertFalse(t.finish(id,"якобы готово",10001));
        assertEquals("незавершённая фраза",t.state().text());t.tick(14_000);assertFalse(t.state().visible());
    }
    @Test public void commandAudioKeepsChosenMicrophoneAndBoundsBacklog()throws Exception{
        CommandAudio stream=new CommandAudio(1,8,8);short[] frame=new short[3200];
        for(int i=0;i<320;i++){frame[i*10]=31000;frame[i*10+8]=(short)(i%2==0?-5000:5000);}
        stream.offerFrame(frame);short[] out=stream.poll();assertEquals(320,out.length);assertEquals(-32768,out[0]);assertEquals(32767,out[1]);
        for(int i=0;i<101;i++)stream.offerFrame(frame);assertTrue(stream.overflow);
    }
}
