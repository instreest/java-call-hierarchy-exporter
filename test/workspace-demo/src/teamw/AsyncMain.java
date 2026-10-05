package teamw;

/** 起点。test/demo には Thread#start() → AsyncJob.run() の規則でしか届かない（workspace.scope=callers でも起点になること） */
public class AsyncMain {
    public static void main(String[] args) {
        new Thread(new AsyncJob()).start();
    }
}
