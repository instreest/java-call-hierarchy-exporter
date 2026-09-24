// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.List;

/**
 * 同梱の契約表: フレームワークが起点として呼ぶメソッド（{@link FrameworkEntries}）。
 *
 * 「フレームワークが呼ぶ」と仕様で決まっているものに絞る。足りなければ行を足す
 * （docs/callback-contracts.md）。javax と jakarta は両方を持つ。
 */
final class BundledFrameworkEntries {

    private BundledFrameworkEntries() {
    }

    private static final String HTTP_REQ_RES_JAVAX =
            "javax.servlet.http.HttpServletRequest,javax.servlet.http.HttpServletResponse";
    private static final String HTTP_REQ_RES_JAKARTA =
            "jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse";

    static final List<String> LINES = List.of(
            // --- JDK ---
            // 起動の入口（JLS 12.1.4）。public static main(String[]) に限らず、引数なしのものと
            // インスタンスメソッドのもの（コンパクトなコンパイル単位の void main() など）も含む
            "main",
            // --- Servlet（javax / jakarta） ---
            "super javax.servlet.http.HttpServlet#doGet(" + HTTP_REQ_RES_JAVAX + ")",
            "super javax.servlet.http.HttpServlet#doPost(" + HTTP_REQ_RES_JAVAX + ")",
            "super javax.servlet.http.HttpServlet#doPut(" + HTTP_REQ_RES_JAVAX + ")",
            "super javax.servlet.http.HttpServlet#doDelete(" + HTTP_REQ_RES_JAVAX + ")",
            "super javax.servlet.http.HttpServlet#service(" + HTTP_REQ_RES_JAVAX + ")",
            "super javax.servlet.GenericServlet#service(javax.servlet.ServletRequest,javax.servlet.ServletResponse)",
            "super javax.servlet.GenericServlet#init()",
            "super javax.servlet.Filter#doFilter(javax.servlet.ServletRequest,javax.servlet.ServletResponse,javax.servlet.FilterChain)",
            "super javax.servlet.ServletContextListener#contextInitialized(javax.servlet.ServletContextEvent)",
            "super javax.servlet.ServletContextListener#contextDestroyed(javax.servlet.ServletContextEvent)",
            "super jakarta.servlet.http.HttpServlet#doGet(" + HTTP_REQ_RES_JAKARTA + ")",
            "super jakarta.servlet.http.HttpServlet#doPost(" + HTTP_REQ_RES_JAKARTA + ")",
            "super jakarta.servlet.http.HttpServlet#doPut(" + HTTP_REQ_RES_JAKARTA + ")",
            "super jakarta.servlet.http.HttpServlet#doDelete(" + HTTP_REQ_RES_JAKARTA + ")",
            "super jakarta.servlet.http.HttpServlet#service(" + HTTP_REQ_RES_JAKARTA + ")",
            "super jakarta.servlet.GenericServlet#service(jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse)",
            "super jakarta.servlet.GenericServlet#init()",
            "super jakarta.servlet.Filter#doFilter(jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse,jakarta.servlet.FilterChain)",
            "super jakarta.servlet.ServletContextListener#contextInitialized(jakarta.servlet.ServletContextEvent)",
            "super jakarta.servlet.ServletContextListener#contextDestroyed(jakarta.servlet.ServletContextEvent)",
            // --- Spring Web ---
            "@org.springframework.web.bind.annotation.RequestMapping",
            "@org.springframework.web.bind.annotation.GetMapping",
            "@org.springframework.web.bind.annotation.PostMapping",
            "@org.springframework.web.bind.annotation.PutMapping",
            "@org.springframework.web.bind.annotation.DeleteMapping",
            "@org.springframework.web.bind.annotation.PatchMapping",
            "@org.springframework.web.bind.annotation.ExceptionHandler",
            "@org.springframework.web.bind.annotation.InitBinder",
            "@org.springframework.web.bind.annotation.ModelAttribute",
            // --- Spring スケジュール・イベント・ライフサイクル ---
            "@org.springframework.scheduling.annotation.Scheduled",
            "@org.springframework.context.event.EventListener",
            "@org.springframework.transaction.event.TransactionalEventListener",
            "super org.springframework.context.ApplicationListener#onApplicationEvent(org.springframework.context.ApplicationEvent)",
            "super org.springframework.beans.factory.InitializingBean#afterPropertiesSet()",
            "super org.springframework.beans.factory.DisposableBean#destroy()",
            "super org.springframework.boot.CommandLineRunner#run(java.lang.String[])",
            "super org.springframework.boot.ApplicationRunner#run(org.springframework.boot.ApplicationArguments)",
            "@javax.annotation.PostConstruct",
            "@javax.annotation.PreDestroy",
            "@jakarta.annotation.PostConstruct",
            "@jakarta.annotation.PreDestroy",
            // --- メッセージング ---
            "@org.springframework.kafka.annotation.KafkaListener",
            "@org.springframework.amqp.rabbit.annotation.RabbitListener",
            "@org.springframework.jms.annotation.JmsListener",
            "super javax.jms.MessageListener#onMessage(javax.jms.Message)",
            "super jakarta.jms.MessageListener#onMessage(jakarta.jms.Message)",
            // --- バッチ ---
            "super org.quartz.Job#execute(org.quartz.JobExecutionContext)",
            "super org.springframework.batch.core.step.tasklet.Tasklet#execute("
                    + "org.springframework.batch.core.StepContribution,org.springframework.batch.core.scope.context.ChunkContext)",
            // --- Struts ---
            "super org.apache.struts.action.Action#execute(org.apache.struts.action.ActionMapping,"
                    + "org.apache.struts.action.ActionForm,javax.servlet.http.HttpServletRequest,"
                    + "javax.servlet.http.HttpServletResponse)",
            // --- テスト ---
            "@org.junit.jupiter.api.Test",
            "@org.junit.jupiter.api.BeforeEach",
            "@org.junit.jupiter.api.AfterEach",
            "@org.junit.jupiter.api.BeforeAll",
            "@org.junit.jupiter.api.AfterAll",
            "@org.junit.jupiter.params.ParameterizedTest",
            "@org.junit.Test",
            "@org.junit.Before",
            "@org.junit.After",
            "@org.testng.annotations.Test");
}
