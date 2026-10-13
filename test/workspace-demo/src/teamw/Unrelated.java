package teamw;

/** test/demo に届かない階層。workspace.scope=callers では CSV に出ず、all では出る */
public class Unrelated {
    public static void standalone() {
        Helper.log("standalone");
    }
}
