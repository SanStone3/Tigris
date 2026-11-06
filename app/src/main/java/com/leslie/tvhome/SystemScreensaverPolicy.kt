package com.leslie.tvhome

/** Only replace our own selection, and only with a known, installed previous dream. */
internal object SystemScreensaverPolicy {
    fun restore(current: String, previous: String, ownPackage: String, installed: Set<String>): String? {
        fun entries(value: String) = value.split(':').filter { it.isNotBlank() }
        fun ours(value: String) = value.substringBefore('/') == ownPackage
        val selected = entries(current)
        if (selected.none(::ours)) return null
        val saved = entries(previous).filter { !ours(it) && it in installed }
        val others = selected.filterNot(::ours)
        val result = (others + saved).distinct()
        return result.takeIf { it.isNotEmpty() }?.joinToString(":")
    }
}
