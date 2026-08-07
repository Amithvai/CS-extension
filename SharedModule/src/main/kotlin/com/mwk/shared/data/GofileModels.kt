package com.mwk.shared.data

import com.fasterxml.jackson.annotation.JsonProperty

data class GofileAccount(@JsonProperty("data") val data: HashMap<String, String>? = null)
data class GofileData(@JsonProperty("contents") val contents: HashMap<String, HashMap<String, String>>? = null)
data class GofileSource(@JsonProperty("data") val data: GofileData? = null)
