package com.kiss.backend.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.temporal.activity.ActivityExecutionContext
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase
import io.temporal.common.interceptors.WorkerInterceptorBase
import org.springframework.stereotype.Component

/**
 * Activity-level observability (MCP "Retry Alerting via Metrics" pattern):
 * - `faldony.temporal.activity.retries`      — attempt > 1 (a retry happened)
 * - `faldony.temporal.activity.failures{activity,error}` — terminal failure,
 *   tagged with activity type + exception class so expected (benign) failures
 *   can be filtered out in alerting.
 *
 * Registered as a Spring bean; the Temporal Spring autoconfigure picks up
 * `WorkerInterceptor` beans and applies them to every worker.
 */
@Component
class TemporalMetricsInterceptor(private val registry: MeterRegistry) : WorkerInterceptorBase() {

    private val retries: Counter = registry.counter("faldony.temporal.activity.retries")

    override fun interceptActivity(next: ActivityInboundCallsInterceptor): ActivityInboundCallsInterceptor =
        object : ActivityInboundCallsInterceptorBase(next) {

            private var currentActivity: String = "unknown"
            private lateinit var ctx: ActivityExecutionContext

            override fun init(context: ActivityExecutionContext) {
                ctx = context
                super.init(context)
            }

            override fun execute(input: ActivityInboundCallsInterceptor.ActivityInput): ActivityInboundCallsInterceptor.ActivityOutput {
                currentActivity = ctx.info.activityType
                if (ctx.info.attempt > 1) {
                    this@TemporalMetricsInterceptor.retries.increment()
                }
                return try {
                    super.execute(input)
                } catch (e: Exception) {
                    this@TemporalMetricsInterceptor.registry.counter(
                        "faldony.temporal.activity.failures",
                        listOf(
                            Tag.of("activity", currentActivity),
                            Tag.of("error", e.javaClass.simpleName)
                        )
                    ).increment()
                    throw e
                }
            }
        }
}