package org.jcoro;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author elwood
 */
public class CoroDispatcher {

    static {
        executorService = Executors.newFixedThreadPool(5);
    }

    private static ExecutorService executorService;

    /**
     * Запускает задачу в контексте, допускающем использование сопрограмм
     * (паузу и возобновление) в одном из потоков пула. Если внутри задачи кто-то позовёт yield(),
     * потом можно будет позвать resume(). Но скорее всего возобновится выполнение уже из другого потока.
     */
    public static void post(Coro coro) {
        executorService.execute(() -> {
            coro.resume();
        });
    }

    /**
     * Запускает задачу в текущем потоке, возвращает экземпляр Coro (возврат управления происходит
     * при вызове yield внутри задачи, или при полном её завершении). Если сопрограмма не была завершена
     * полностью (был вызван yield), то её выполнение можно возобновить, позвав coro.resume();
     */
//    public static Coro run(ICoroRunnable runnable) {
//        Coro.getOrCreate()
//        runnable
//    }
}
