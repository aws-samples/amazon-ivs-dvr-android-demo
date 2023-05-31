package com.amazon.ivs.livetovod.common

import timber.log.Timber

private const val TIMBER_TAG = "LiveToVod"

/**
 * Makes logged out class names clickable in Logcat
 */
class LineNumberDebugTree : Timber.DebugTree() {

    override fun createStackElementTag(element: StackTraceElement) =
        "$TIMBER_TAG: (${element.fileName}:${element.lineNumber}) #${element.methodName} "
}
