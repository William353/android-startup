package com.rousetime.android_startup

import android.content.Context
import android.os.Looper
import com.rousetime.android_startup.dispatcher.ManagerDispatcher
import com.rousetime.android_startup.model.LoggerLevel
import com.rousetime.android_startup.model.StartupSortStore
import com.rousetime.android_startup.run.StartupRunnable
import com.rousetime.android_startup.sort.TopologySort
import com.rousetime.android_startup.utils.StartupLogUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Created by idisfkj on 2020/7/24.
 * Email : idisfkj@gmail.com.
 */
class StartupManager private constructor(
    private val context: Context,
    private val startupList: List<AndroidStartup<*>>,
    private val needAwaitCount: AtomicInteger,
    loggerLevel: LoggerLevel
) {


    private var mAwaitCountDownLatch: CountDownLatch? = null
    private var mStartTime: Long = 0L

    companion object {
        private const val AWAIT_TIMEOUT = 10000L

        class Builder {

            private var mStartupList = mutableListOf<AndroidStartup<*>>()
            private var mNeedAwaitCount = AtomicInteger()
            private var mLoggerLevel = LoggerLevel.NONE

            fun addStartup(startup: AndroidStartup<*>) = apply {
                mStartupList.add(startup)
                if (startup.isNeedWait() && !startup.isOnMainThread()) {
                    mNeedAwaitCount.incrementAndGet()
                }
            }

            fun setLoggerLevel(level: LoggerLevel) = apply {
                mLoggerLevel = level
            }

            fun build(context: Context): StartupManager {
                return StartupManager(
                    context,
                    mStartupList,
                    mNeedAwaitCount,
                    mLoggerLevel
                )
            }
        }
    }

    init {
        StartupLogUtils.level = loggerLevel
    }

    fun start() = apply {
        if (startupList.isNullOrEmpty()) {
            throw RuntimeException("Startup is empty, add at least one startup.")
        }

        if (Looper.getMainLooper() != Looper.myLooper()) {
            throw RuntimeException("start method must be call in MainThread.")
        }

        if (mAwaitCountDownLatch != null) {
            throw RuntimeException("start method repeated call.")
        }

        mAwaitCountDownLatch = CountDownLatch(needAwaitCount.get())
        TopologySort.sort(startupList).run {
            mStartTime = System.nanoTime()
            execute(this)
        }
    }

    private fun execute(sortStore: StartupSortStore) {

        StartupLogUtils.d("execute start: size of ${sortStore.mainSortResult.size + sortStore.ioSortResult.size}")

        sortStore.ioSortResult.forEach {

            StartupLogUtils.d("IOThread Startup ${it::class.java.simpleName} being executing.")

            it.createExecutor().execute(StartupRunnable(context, it, sortStore, mDefaultManagerDispatcher))
        }

        sortStore.mainSortResult.forEach {

            StartupLogUtils.d("MainThread Startup ${it::class.java.simpleName} being executing.")

            StartupRunnable(context, it, sortStore, mDefaultManagerDispatcher).run()
        }
    }

    /**
     * When dependencyParent startup completed, to notify children
     */
    private val mDefaultManagerDispatcher by lazy {
        object : ManagerDispatcher {

            override fun notifyChildren(dependencyParent: Startup<*>, sortStore: StartupSortStore) {
                sortStore.clazzChildrenMap[dependencyParent::class.java]?.forEach {
                    sortStore.clazzMap[it]?.toNotify()

                    StartupLogUtils.d("notifyChildren => parent ${dependencyParent::class.java.simpleName} to notify children ${it.simpleName}")
                }
                if (dependencyParent.isNeedWait()) {
                    needAwaitCount.incrementAndGet()
                    mAwaitCountDownLatch?.countDown()
                }

                StartupLogUtils.d("Startup ${dependencyParent::class.java.simpleName} was completed.")
            }

        }
    }

    fun await() {
        if (mAwaitCountDownLatch == null) {
            throw RuntimeException("must be call start method before call await method.")
        }

        try {
            mAwaitCountDownLatch?.await(AWAIT_TIMEOUT, TimeUnit.MILLISECONDS)

            StartupLogUtils.d("totalTime: ${(System.nanoTime() - mStartTime) / 1000L / 1000L / 1000L}")
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }
}