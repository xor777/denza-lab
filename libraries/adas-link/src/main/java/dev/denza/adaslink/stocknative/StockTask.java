package dev.denza.adaslink.stocknative;

/** Stable original Runnable identity; a host ledger binds instances at their first observed post. */
public final class StockTask implements Runnable {
    public final String originalClass, purpose;
    private final Runnable action;
    public StockTask(String originalClass,String purpose,Runnable action){this.originalClass=originalClass;this.purpose=purpose;this.action=action;}
    @Override public void run(){action.run();}
    @Override public String toString(){return originalClass+" ("+purpose+")";}
}
