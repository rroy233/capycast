package com.roy233.tvcast.data.auth

import com.google.gson.JsonObject

/** 网页接口外层结果和扫码状态是两个独立的状态码。 */
internal sealed interface InternationalQrPoll {
    data class Pending(val code: Int, val message: String) : InternationalQrPoll

    data class Failure(val code: Int, val message: String) : InternationalQrPoll

    data class Confirmed(val refreshToken: String) : InternationalQrPoll
}

internal fun parseInternationalQrPoll(response: JsonObject): InternationalQrPoll {
    val outer =
        response.get("code")?.asInt ?: return InternationalQrPoll.Failure(-1, "missing code")
    if (outer != 0)
        return InternationalQrPoll.Failure(outer, response.get("message")?.asString.orEmpty())
    val data =
        response.getAsJsonObject("data") ?: return InternationalQrPoll.Failure(-1, "missing data")
    val status = data.get("code")?.asInt ?: return InternationalQrPoll.Failure(-1, "missing status")
    val message = data.get("message")?.asString.orEmpty()
    return when (status) {
        0 -> InternationalQrPoll.Confirmed(data.get("refresh_token")?.asString.orEmpty())
        86101,
        86090,
        86038 -> InternationalQrPoll.Pending(status, message)
        else -> InternationalQrPoll.Failure(status, message)
    }
}
