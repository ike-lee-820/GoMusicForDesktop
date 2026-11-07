package com.google.common.util.concurrent

import java.util.concurrent.Executor

interface ListenableFuture<T> {
    fun addListener(listener: java.lang.Runnable, executor: Executor)
    fun get(): T
    fun isDone(): Boolean = true
}
object Futures {
    fun <T> immediateFuture(v: T): ListenableFuture<T> = object : ListenableFuture<T> {
        override fun addListener(listener: java.lang.Runnable, executor: Executor) { executor.execute(listener) }
        override fun get(): T = v
    }
}
