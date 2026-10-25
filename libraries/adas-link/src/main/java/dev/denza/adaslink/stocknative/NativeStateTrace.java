package dev.denza.adaslink.stocknative;

/** Optional host observer of computed sender state. Disabled outside replay workers. */
final class NativeStateTrace {
    private static final ThreadLocal<StockRuntime> CURRENT=new ThreadLocal<>();
    private NativeStateTrace(){}
    static StockRuntime attach(StockRuntime runtime){StockRuntime previous=CURRENT.get();if(runtime==null)CURRENT.remove();else CURRENT.set(runtime);return previous;}
    static StockRuntime enter(Object receiver,String method,Object...arguments){StockRuntime runtime=CURRENT.get();if(runtime!=null)runtime.stateEnter(method,receiver,arguments);return runtime;}
    static void exit(StockRuntime runtime,Object receiver,String method,Object result){if(runtime!=null)runtime.stateExit(method,receiver,result);}
}
