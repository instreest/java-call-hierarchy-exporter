package fx.app;

import missing.lib.RemoteClient;
import missing.lib.*;

public class Legacy {
    static void callRemote() {
        RemoteClient.send("x");
        Other.go();
        new RemoteClient().close();
    }

    void unusedDead() {
        callRemote();
    }
}
