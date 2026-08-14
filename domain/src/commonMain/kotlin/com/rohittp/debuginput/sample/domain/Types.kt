package com.rohittp.debuginput.sample.domain

import com.rohittp.debuginput.DebugInput

/**
 * One debug input of every supported type, so the dogfood build exercises the whole type
 * table rather than the `Int` the first milestone shipped.
 *
 * These live at the top level of one file on purpose: the page's section for all of them
 * is `Types`, which keeps the sweep readable on a phone.
 */

enum class Tier { FREE, PRO, TEAM }

// ---- Scalars ----

@DebugInput(docs = "Requests allowed per minute")
val requestsPerMinute: Int = 60

@DebugInput(docs = "Cache budget in bytes")
val cacheBudgetBytes: Long = 50_000_000L

@DebugInput(docs = "Retries before giving up")
val retryLimit: Short = 3

@DebugInput(docs = "Compression level, 0 to 9")
val compressionLevel: Byte = 6

@DebugInput(docs = "Animation speed multiplier")
val animationScale: Float = 1.0f

@DebugInput(docs = "Latitude used when location is unavailable")
val fallbackLatitude: Double = 12.9716

@DebugInput(docs = "Whether the experimental renderer is on")
val useExperimentalRenderer: Boolean = false

@DebugInput(docs = "Separator used in exported CSV")
val csvSeparator: Char = ','

@DebugInput(docs = "Base URL for API calls")
val apiBaseUrl: String = "https://api.example.com"

@DebugInput(docs = "Tier applied to a new account")
val defaultTier: Tier = Tier.FREE

// ---- Collections ----

@DebugInput(docs = "Hosts tried in order")
val apiHosts: List<String> = listOf("api.example.com", "cdn.example.com")

@DebugInput(docs = "HTTP statuses treated as retryable")
val retryableStatuses: Set<Int> = setOf(429, 503)

@DebugInput(docs = "Feature flags read at startup")
val startupFlags: Array<String> = arrayOf("fast-boot", "prefetch")

// ---- Primitive arrays ----

@DebugInput(docs = "Backoff delays in milliseconds")
val backoffMillis: IntArray = intArrayOf(100, 400, 1_600)

@DebugInput(docs = "Rollover thresholds in bytes")
val rolloverThresholds: LongArray = longArrayOf(1_000L, 1_000_000L)

@DebugInput(docs = "Ports probed on the local network")
val probePorts: ShortArray = shortArrayOf(80, 443)

@DebugInput(docs = "Magic bytes identifying the cache format")
val cacheMagic: ByteArray = byteArrayOf(0x7f, 0x45)

@DebugInput(docs = "Sampling weights per bucket")
val samplingWeights: FloatArray = floatArrayOf(0.25f, 0.75f)

/** The case ADR-0009 is about: mutable, and the tuning code most likely to want it. */
@DebugInput(docs = "Ranking weights, tuned by hand")
val rankingWeights: DoubleArray = doubleArrayOf(1.0, 0.5, 0.25)

@DebugInput(docs = "Which pipeline stages are enabled")
val stagesEnabled: BooleanArray = booleanArrayOf(true, false, true)

@DebugInput(docs = "Characters stripped from user input")
val strippedChars: CharArray = charArrayOf('\n', '\t')

// ---- Tuples ----

@DebugInput(docs = "Retry count and the strategy naming it")
val retryStrategy: Pair<Int, String> = 3 to "exponential"

@DebugInput(docs = "Width, height, and whether to letterbox")
val viewport: Triple<Int, Int, Boolean> = Triple(1080, 1920, true)
