package dev.denza.vehiclecontrol.probe;

import java.util.List;
import java.util.function.BooleanSupplier;

/** One immutable whole-utterance plan. Transport gates are checked before opening the controller. */
final class VoiceCommandRoute {
    final VoiceCommands.Assessment assessment;
    private VoiceCommandRoute(VoiceCommands.Assessment assessment){this.assessment=assessment;}
    static VoiceCommandRoute prepare(String endpointJson)throws Exception{return new VoiceCommandRoute(VoiceCommands.assessVosk(endpointJson));}
    String description(){return VoiceCommands.describe(assessment.plan());}
    VoiceCommands.Execution execute(NativeController controller,BooleanSupplier current,boolean enabled,boolean confirmed){
        if(!enabled)return new VoiceCommands.Execution(List.of(),false,"Выполнение голосовых команд выключено; ничего не отправлено");
        if(assessment.needsConfirmation()&&!confirmed)return new VoiceCommands.Execution(List.of(),false,"Нужно подтверждение плана; ничего не отправлено");
        return VoiceCommands.execute(assessment.plan(),controller,current);
    }
}
