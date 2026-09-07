package fx.service;

import fx.dao.Dao;

public class Notifier implements Service {
    private Dao dao;
    private final Service next;

    public Notifier(Dao dao, Service next) {
        this.dao = dao;
        this.next = next;
    }

    public Notifier(Service next) {
        this.next = next;
    }

    @Override
    public void execute() {
        if (dao != null) {
            dao.describe();
        }
        next.execute();
        ping();
    }

    void ping() {
        pong();
    }

    void pong() {
        ping();
    }
}
