// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.List;

/**
 * 同梱の契約表: JDK のメソッドが、渡された値のどのメソッドを呼び戻すか（{@link CallbackContracts}）。
 *
 * 呼び出し先のキーは JDT のバインディングが返す<b>宣言型</b>で書く。{@code list.forEach(...)} は
 * {@code List} が {@code forEach} を上書きしていないので {@code java.lang.Iterable#forEach} に、
 * {@code executor.submit(...)} は {@code ExecutorService#submit} に解決される。
 * 静的な型によって宣言型が変わるものは、それぞれの型で行を持つ。
 *
 * 全部を網羅するのではなく、「呼び戻される」と言い切れて、実務で経路が切れて困るものに絞る。
 * 足りなければ行を足す（docs/callback-contracts-qa.md）。
 */
final class JdkCallbacks {

    private JdkCallbacks() {
    }

    private static final String CONSUMER = "java.util.function.Consumer";
    private static final String BI_CONSUMER = "java.util.function.BiConsumer";
    private static final String FUNCTION = "java.util.function.Function";
    private static final String BI_FUNCTION = "java.util.function.BiFunction";
    private static final String PREDICATE = "java.util.function.Predicate";
    private static final String SUPPLIER = "java.util.function.Supplier";
    private static final String RUNNABLE = "java.lang.Runnable";
    private static final String CALLABLE = "java.util.concurrent.Callable";
    private static final String COMPARATOR = "java.util.Comparator";

    private static final String ACCEPT1 = "accept(java.lang.Object)";
    private static final String ACCEPT2 = "accept(java.lang.Object,java.lang.Object)";
    private static final String APPLY1 = "apply(java.lang.Object)";
    private static final String APPLY2 = "apply(java.lang.Object,java.lang.Object)";
    private static final String TEST1 = "test(java.lang.Object)";
    private static final String COMPARE = "compare(java.lang.Object,java.lang.Object)";

    static final List<String> LINES = List.of(
            // --- スレッド・非同期 ---
            "java.lang.Thread#start() -> c* : run()",
            "java.lang.Thread#start() -> r : run()",
            "java.lang.Thread#run() -> c* : run()",
            "java.util.concurrent.Executor#execute(" + RUNNABLE + ") -> a0 : run()",
            "java.util.concurrent.ExecutorService#execute(" + RUNNABLE + ") -> a0 : run()",
            "java.util.concurrent.ExecutorService#submit(" + RUNNABLE + ") -> a0 : run()",
            "java.util.concurrent.ExecutorService#submit(" + RUNNABLE + ",java.lang.Object) -> a0 : run()",
            "java.util.concurrent.ExecutorService#submit(" + CALLABLE + ") -> a0 : call()",
            "java.util.concurrent.ScheduledExecutorService#schedule(" + RUNNABLE
                    + ",long,java.util.concurrent.TimeUnit) -> a0 : run()",
            "java.util.concurrent.ScheduledExecutorService#schedule(" + CALLABLE
                    + ",long,java.util.concurrent.TimeUnit) -> a0 : call()",
            "java.util.concurrent.ScheduledExecutorService#scheduleAtFixedRate(" + RUNNABLE
                    + ",long,long,java.util.concurrent.TimeUnit) -> a0 : run()",
            "java.util.concurrent.ScheduledExecutorService#scheduleWithFixedDelay(" + RUNNABLE
                    + ",long,long,java.util.concurrent.TimeUnit) -> a0 : run()",
            "java.util.concurrent.CompletableFuture#runAsync(" + RUNNABLE + ") -> a0 : run()",
            "java.util.concurrent.CompletableFuture#runAsync(" + RUNNABLE
                    + ",java.util.concurrent.Executor) -> a0 : run()",
            "java.util.concurrent.CompletableFuture#supplyAsync(" + SUPPLIER + ") -> a0 : get()",
            "java.util.concurrent.CompletableFuture#supplyAsync(" + SUPPLIER
                    + ",java.util.concurrent.Executor) -> a0 : get()",
            "java.util.concurrent.CompletableFuture#thenApply(" + FUNCTION + ") -> a0 : " + APPLY1,
            "java.util.concurrent.CompletableFuture#thenAccept(" + CONSUMER + ") -> a0 : " + ACCEPT1,
            "java.util.concurrent.CompletableFuture#thenRun(" + RUNNABLE + ") -> a0 : run()",
            "java.util.concurrent.CompletableFuture#thenCompose(" + FUNCTION + ") -> a0 : " + APPLY1,
            "java.util.Timer#schedule(java.util.TimerTask,long) -> a0 : run()",
            "java.util.Timer#schedule(java.util.TimerTask,long,long) -> a0 : run()",
            "java.util.Timer#scheduleAtFixedRate(java.util.TimerTask,long,long) -> a0 : run()",
            // --- コレクション ---
            "java.lang.Iterable#forEach(" + CONSUMER + ") -> a0 : " + ACCEPT1,
            "java.util.Collection#forEach(" + CONSUMER + ") -> a0 : " + ACCEPT1,
            "java.util.List#forEach(" + CONSUMER + ") -> a0 : " + ACCEPT1,
            "java.util.Set#forEach(" + CONSUMER + ") -> a0 : " + ACCEPT1,
            "java.util.Map#forEach(" + BI_CONSUMER + ") -> a0 : " + ACCEPT2,
            "java.util.Collection#removeIf(" + PREDICATE + ") -> a0 : " + TEST1,
            "java.util.List#sort(" + COMPARATOR + ") -> a0 : " + COMPARE,
            "java.util.Collections#sort(java.util.List," + COMPARATOR + ") -> a1 : " + COMPARE,
            "java.util.Arrays#sort(java.lang.Object[]," + COMPARATOR + ") -> a1 : " + COMPARE,
            "java.util.Map#computeIfAbsent(java.lang.Object," + FUNCTION + ") -> a1 : " + APPLY1,
            "java.util.Map#computeIfPresent(java.lang.Object," + BI_FUNCTION + ") -> a1 : " + APPLY2,
            "java.util.Map#compute(java.lang.Object," + BI_FUNCTION + ") -> a1 : " + APPLY2,
            "java.util.Map#merge(java.lang.Object,java.lang.Object," + BI_FUNCTION + ") -> a2 : " + APPLY2,
            // --- Stream / Optional ---
            "java.util.stream.Stream#forEach(" + CONSUMER + ") -> a0 : " + ACCEPT1,
            "java.util.stream.Stream#forEachOrdered(" + CONSUMER + ") -> a0 : " + ACCEPT1,
            "java.util.stream.Stream#map(" + FUNCTION + ") -> a0 : " + APPLY1,
            "java.util.stream.Stream#flatMap(" + FUNCTION + ") -> a0 : " + APPLY1,
            "java.util.stream.Stream#filter(" + PREDICATE + ") -> a0 : " + TEST1,
            "java.util.stream.Stream#peek(" + CONSUMER + ") -> a0 : " + ACCEPT1,
            "java.util.stream.Stream#anyMatch(" + PREDICATE + ") -> a0 : " + TEST1,
            "java.util.stream.Stream#allMatch(" + PREDICATE + ") -> a0 : " + TEST1,
            "java.util.stream.Stream#noneMatch(" + PREDICATE + ") -> a0 : " + TEST1,
            "java.util.stream.Stream#sorted(" + COMPARATOR + ") -> a0 : " + COMPARE,
            "java.util.Optional#map(" + FUNCTION + ") -> a0 : " + APPLY1,
            "java.util.Optional#flatMap(" + FUNCTION + ") -> a0 : " + APPLY1,
            "java.util.Optional#filter(" + PREDICATE + ") -> a0 : " + TEST1,
            "java.util.Optional#ifPresent(" + CONSUMER + ") -> a0 : " + ACCEPT1,
            "java.util.Optional#ifPresentOrElse(" + CONSUMER + "," + RUNNABLE + ") -> a0 : " + ACCEPT1,
            "java.util.Optional#ifPresentOrElse(" + CONSUMER + "," + RUNNABLE + ") -> a1 : run()",
            "java.util.Optional#orElseGet(" + SUPPLIER + ") -> a0 : get()");
}
