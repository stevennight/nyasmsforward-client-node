package app.nya.smsforward.node.node

import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.ConnectionVerdict
import app.nya.smsforward.node.net.FailureKind
import app.nya.smsforward.node.net.ServerUrlResult

/** The words shown to the user for a failed request. Kept in one place so the status, pairing and settings screens agree. */
fun describeFailure(f: ApiResult.Failure): String = when {
    f.verdict == ConnectionVerdict.NEEDS_PAIRING -> "令牌已失效，需要重新配对"
    f.kind == FailureKind.NETWORK -> "无法连接到服务器，请检查地址和网络"
    f.kind == FailureKind.TIMEOUT -> "连接服务器超时"
    f.kind == FailureKind.TLS -> "服务器证书无效或不受信任（域名不匹配、证书过期，或使用了自签名证书）"
    f.kind == FailureKind.NOT_OUR_SERVER || f.verdict == ConnectionVerdict.ADDRESS_SUSPECT ->
        "这个地址不像 NyaSmsForward 服务器，请检查地址（是否需要 https://，或被反向代理拦截）"
    f.status == 429 -> "请求太频繁，请稍后再试"
    f.status != null && f.status >= 500 -> "服务器暂时不可用（HTTP ${f.status}），请稍后再试"
    else -> "服务器拒绝了请求${f.code?.let { "（$it）" } ?: f.status?.let { "（HTTP $it）" }.orEmpty()}"
}

/** Extra wording for the pairing call, where the server's own codes are more helpful than a status. */
fun describePairFailure(f: ApiResult.Failure): String = when (f.code) {
    "invalid_pair_code" -> "配对码错误、已过期或已被使用"
    "too_many_attempts" -> "尝试次数太多，请稍后再试"
    "pair_kind_mismatch" -> "这是客户端的配对码。接收端需要在 Web「设备与客户端」里选择“接收端手机”来生成配对码"
    else -> describeFailure(f)
}

fun describeUrlProblem(reason: ServerUrlResult.Reason): String = when (reason) {
    ServerUrlResult.Reason.EMPTY -> "请填写服务器地址"
    ServerUrlResult.Reason.BAD_SCHEME -> "地址需要以 https:// 开头"
    ServerUrlResult.Reason.BAD_HOST -> "服务器地址格式不正确"
    ServerUrlResult.Reason.PUBLIC_HTTP -> "公网地址必须使用 HTTPS（只有 localhost / 局域网地址可以用 http）"
}
