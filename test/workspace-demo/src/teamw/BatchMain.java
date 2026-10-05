package teamw;

/** ワークスペースの他のプロジェクトの起点。ここから test/demo のメソッドに届く */
public class BatchMain {
    public static void main(String[] args) {
        new BatchJob().run();
        Unrelated.standalone();
    }
}
