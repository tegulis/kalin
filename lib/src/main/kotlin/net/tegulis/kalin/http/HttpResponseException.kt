package net.tegulis.kalin.http

/** Base class of all Kalin exceptions. */
public open class KalinException(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Throw from a handler to answer with [status]. The default mapper writes JSON or plain text depending on the `Accept`
 * header. Subclasses exist for the common codes (for example [NotFoundResponse]).
 */
public open class HttpResponseException
@JvmOverloads
constructor(
    /** The response status code. */
    public val status: Int,
    message: String = "",
    /** Extra details included in the response. */
    public val details: Map<String, String> = emptyMap(),
) : KalinException(message) {
    /** Creates an exception from an [HttpStatus]. */
    public constructor(
        status: HttpStatus,
        message: String = status.message,
        details: Map<String, String> = emptyMap(),
    ) : this(status.code, message, details)
}

/** Redirect response (default `302 Found`). */
public open class RedirectResponse
@JvmOverloads
constructor(
    status: HttpStatus = HttpStatus.FOUND,
    message: String = status.message,
    details: Map<String, String> = emptyMap(),
) : HttpResponseException(status, message, details)

/** Answers with `CONTINUE`. */
public open class ContinueResponse
@JvmOverloads
constructor(message: String = HttpStatus.CONTINUE.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.CONTINUE, message, details)

/** Answers with `SWITCHING_PROTOCOLS`. */
public open class SwitchingProtocolsResponse
@JvmOverloads
constructor(message: String = HttpStatus.SWITCHING_PROTOCOLS.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.SWITCHING_PROTOCOLS, message, details)

/** Answers with `PROCESSING`. */
public open class ProcessingResponse
@JvmOverloads
constructor(message: String = HttpStatus.PROCESSING.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.PROCESSING, message, details)

/** Answers with `EARLY_HINTS`. */
public open class EarlyHintsResponse
@JvmOverloads
constructor(message: String = HttpStatus.EARLY_HINTS.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.EARLY_HINTS, message, details)

/** Answers with `OK`. */
public open class OkResponse
@JvmOverloads
constructor(message: String = HttpStatus.OK.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.OK, message, details)

/** Answers with `CREATED`. */
public open class CreatedResponse
@JvmOverloads
constructor(message: String = HttpStatus.CREATED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.CREATED, message, details)

/** Answers with `ACCEPTED`. */
public open class AcceptedResponse
@JvmOverloads
constructor(message: String = HttpStatus.ACCEPTED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.ACCEPTED, message, details)

/** Answers with `NON_AUTHORITATIVE_INFORMATION`. */
public open class NonAuthoritativeInformationResponse
@JvmOverloads
constructor(
    message: String = HttpStatus.NON_AUTHORITATIVE_INFORMATION.message,
    details: Map<String, String> = emptyMap(),
) : HttpResponseException(HttpStatus.NON_AUTHORITATIVE_INFORMATION, message, details)

/** Answers with `NO_CONTENT`. */
public open class NoContentResponse
@JvmOverloads
constructor(message: String = HttpStatus.NO_CONTENT.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.NO_CONTENT, message, details)

/** Answers with `RESET_CONTENT`. */
public open class ResetContentResponse
@JvmOverloads
constructor(message: String = HttpStatus.RESET_CONTENT.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.RESET_CONTENT, message, details)

/** Answers with `PARTIAL_CONTENT`. */
public open class PartialContentResponse
@JvmOverloads
constructor(message: String = HttpStatus.PARTIAL_CONTENT.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.PARTIAL_CONTENT, message, details)

/** Answers with `MULTI_STATUS`. */
public open class MultiStatusResponse
@JvmOverloads
constructor(message: String = HttpStatus.MULTI_STATUS.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.MULTI_STATUS, message, details)

/** Answers with `ALREADY_REPORTED`. */
public open class AlreadyReportedResponse
@JvmOverloads
constructor(message: String = HttpStatus.ALREADY_REPORTED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.ALREADY_REPORTED, message, details)

/** Answers with `IM_USED`. */
public open class ImUsedResponse
@JvmOverloads
constructor(message: String = HttpStatus.IM_USED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.IM_USED, message, details)

/** Answers with `MULTIPLE_CHOICES`. */
public open class MultipleChoicesResponse
@JvmOverloads
constructor(message: String = HttpStatus.MULTIPLE_CHOICES.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.MULTIPLE_CHOICES, message, details)

/** Answers with `MOVED_PERMANENTLY`. */
public open class MovedPermanentlyResponse
@JvmOverloads
constructor(message: String = HttpStatus.MOVED_PERMANENTLY.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.MOVED_PERMANENTLY, message, details)

/** Answers with `FOUND`. */
public open class FoundResponse
@JvmOverloads
constructor(message: String = HttpStatus.FOUND.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.FOUND, message, details)

/** Answers with `SEE_OTHER`. */
public open class SeeOtherResponse
@JvmOverloads
constructor(message: String = HttpStatus.SEE_OTHER.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.SEE_OTHER, message, details)

/** Answers with `NOT_MODIFIED`. */
public open class NotModifiedResponse
@JvmOverloads
constructor(message: String = HttpStatus.NOT_MODIFIED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.NOT_MODIFIED, message, details)

/** Answers with `USE_PROXY`. */
public open class UseProxyResponse
@JvmOverloads
constructor(message: String = HttpStatus.USE_PROXY.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.USE_PROXY, message, details)

/** Answers with `TEMPORARY_REDIRECT`. */
public open class TemporaryRedirectResponse
@JvmOverloads
constructor(message: String = HttpStatus.TEMPORARY_REDIRECT.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.TEMPORARY_REDIRECT, message, details)

/** Answers with `PERMANENT_REDIRECT`. */
public open class PermanentRedirectResponse
@JvmOverloads
constructor(message: String = HttpStatus.PERMANENT_REDIRECT.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.PERMANENT_REDIRECT, message, details)

/** Answers with `BAD_REQUEST`. */
public open class BadRequestResponse
@JvmOverloads
constructor(message: String = HttpStatus.BAD_REQUEST.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.BAD_REQUEST, message, details)

/** Answers with `UNAUTHORIZED`. */
public open class UnauthorizedResponse
@JvmOverloads
constructor(message: String = HttpStatus.UNAUTHORIZED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.UNAUTHORIZED, message, details)

/** Answers with `PAYMENT_REQUIRED`. */
public open class PaymentRequiredResponse
@JvmOverloads
constructor(message: String = HttpStatus.PAYMENT_REQUIRED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.PAYMENT_REQUIRED, message, details)

/** Answers with `FORBIDDEN`. */
public open class ForbiddenResponse
@JvmOverloads
constructor(message: String = HttpStatus.FORBIDDEN.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.FORBIDDEN, message, details)

/** Answers with `NOT_FOUND`. */
public open class NotFoundResponse
@JvmOverloads
constructor(message: String = HttpStatus.NOT_FOUND.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.NOT_FOUND, message, details)

/** Answers with `METHOD_NOT_ALLOWED`. */
public open class MethodNotAllowedResponse
@JvmOverloads
constructor(message: String = HttpStatus.METHOD_NOT_ALLOWED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.METHOD_NOT_ALLOWED, message, details)

/** Answers with `NOT_ACCEPTABLE`. */
public open class NotAcceptableResponse
@JvmOverloads
constructor(message: String = HttpStatus.NOT_ACCEPTABLE.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.NOT_ACCEPTABLE, message, details)

/** Answers with `PROXY_AUTHENTICATION_REQUIRED`. */
public open class ProxyAuthenticationRequiredResponse
@JvmOverloads
constructor(
    message: String = HttpStatus.PROXY_AUTHENTICATION_REQUIRED.message,
    details: Map<String, String> = emptyMap(),
) : HttpResponseException(HttpStatus.PROXY_AUTHENTICATION_REQUIRED, message, details)

/** Answers with `REQUEST_TIMEOUT`. */
public open class RequestTimeoutResponse
@JvmOverloads
constructor(message: String = HttpStatus.REQUEST_TIMEOUT.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.REQUEST_TIMEOUT, message, details)

/** Answers with `CONFLICT`. */
public open class ConflictResponse
@JvmOverloads
constructor(message: String = HttpStatus.CONFLICT.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.CONFLICT, message, details)

/** Answers with `GONE`. */
public open class GoneResponse
@JvmOverloads
constructor(message: String = HttpStatus.GONE.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.GONE, message, details)

/** Answers with `LENGTH_REQUIRED`. */
public open class LengthRequiredResponse
@JvmOverloads
constructor(message: String = HttpStatus.LENGTH_REQUIRED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.LENGTH_REQUIRED, message, details)

/** Answers with `PRECONDITION_FAILED`. */
public open class PreconditionFailedResponse
@JvmOverloads
constructor(message: String = HttpStatus.PRECONDITION_FAILED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.PRECONDITION_FAILED, message, details)

/** Answers with `CONTENT_TOO_LARGE`. */
public open class ContentTooLargeResponse
@JvmOverloads
constructor(message: String = HttpStatus.CONTENT_TOO_LARGE.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.CONTENT_TOO_LARGE, message, details)

/** Answers with `URI_TOO_LONG`. */
public open class UriTooLongResponse
@JvmOverloads
constructor(message: String = HttpStatus.URI_TOO_LONG.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.URI_TOO_LONG, message, details)

/** Answers with `UNSUPPORTED_MEDIA_TYPE`. */
public open class UnsupportedMediaTypeResponse
@JvmOverloads
constructor(message: String = HttpStatus.UNSUPPORTED_MEDIA_TYPE.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message, details)

/** Answers with `RANGE_NOT_SATISFIABLE`. */
public open class RangeNotSatisfiableResponse
@JvmOverloads
constructor(message: String = HttpStatus.RANGE_NOT_SATISFIABLE.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.RANGE_NOT_SATISFIABLE, message, details)

/** Answers with `EXPECTATION_FAILED`. */
public open class ExpectationFailedResponse
@JvmOverloads
constructor(message: String = HttpStatus.EXPECTATION_FAILED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.EXPECTATION_FAILED, message, details)

/** Answers with `IM_A_TEAPOT`. */
public open class ImATeapotResponse
@JvmOverloads
constructor(message: String = HttpStatus.IM_A_TEAPOT.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.IM_A_TEAPOT, message, details)

/** Answers with `MISDIRECTED_REQUEST`. */
public open class MisdirectedRequestResponse
@JvmOverloads
constructor(message: String = HttpStatus.MISDIRECTED_REQUEST.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.MISDIRECTED_REQUEST, message, details)

/** Answers with `UNPROCESSABLE_CONTENT`. */
public open class UnprocessableContentResponse
@JvmOverloads
constructor(message: String = HttpStatus.UNPROCESSABLE_CONTENT.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.UNPROCESSABLE_CONTENT, message, details)

/** Answers with `LOCKED`. */
public open class LockedResponse
@JvmOverloads
constructor(message: String = HttpStatus.LOCKED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.LOCKED, message, details)

/** Answers with `FAILED_DEPENDENCY`. */
public open class FailedDependencyResponse
@JvmOverloads
constructor(message: String = HttpStatus.FAILED_DEPENDENCY.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.FAILED_DEPENDENCY, message, details)

/** Answers with `TOO_EARLY`. */
public open class TooEarlyResponse
@JvmOverloads
constructor(message: String = HttpStatus.TOO_EARLY.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.TOO_EARLY, message, details)

/** Answers with `UPGRADE_REQUIRED`. */
public open class UpgradeRequiredResponse
@JvmOverloads
constructor(message: String = HttpStatus.UPGRADE_REQUIRED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.UPGRADE_REQUIRED, message, details)

/** Answers with `PRECONDITION_REQUIRED`. */
public open class PreconditionRequiredResponse
@JvmOverloads
constructor(message: String = HttpStatus.PRECONDITION_REQUIRED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.PRECONDITION_REQUIRED, message, details)

/** Answers with `TOO_MANY_REQUESTS`. */
public open class TooManyRequestsResponse
@JvmOverloads
constructor(message: String = HttpStatus.TOO_MANY_REQUESTS.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.TOO_MANY_REQUESTS, message, details)

/** Answers with `REQUEST_HEADER_FIELDS_TOO_LARGE`. */
public open class RequestHeaderFieldsTooLargeResponse
@JvmOverloads
constructor(
    message: String = HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE.message,
    details: Map<String, String> = emptyMap(),
) : HttpResponseException(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, message, details)

/** Answers with `UNAVAILABLE_FOR_LEGAL_REASONS`. */
public open class UnavailableForLegalReasonsResponse
@JvmOverloads
constructor(
    message: String = HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS.message,
    details: Map<String, String> = emptyMap(),
) : HttpResponseException(HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS, message, details)

/** Answers with `INTERNAL_SERVER_ERROR`. */
public open class InternalServerErrorResponse
@JvmOverloads
constructor(message: String = HttpStatus.INTERNAL_SERVER_ERROR.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.INTERNAL_SERVER_ERROR, message, details)

/** Answers with `NOT_IMPLEMENTED`. */
public open class NotImplementedResponse
@JvmOverloads
constructor(message: String = HttpStatus.NOT_IMPLEMENTED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.NOT_IMPLEMENTED, message, details)

/** Answers with `BAD_GATEWAY`. */
public open class BadGatewayResponse
@JvmOverloads
constructor(message: String = HttpStatus.BAD_GATEWAY.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.BAD_GATEWAY, message, details)

/** Answers with `SERVICE_UNAVAILABLE`. */
public open class ServiceUnavailableResponse
@JvmOverloads
constructor(message: String = HttpStatus.SERVICE_UNAVAILABLE.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.SERVICE_UNAVAILABLE, message, details)

/** Answers with `GATEWAY_TIMEOUT`. */
public open class GatewayTimeoutResponse
@JvmOverloads
constructor(message: String = HttpStatus.GATEWAY_TIMEOUT.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.GATEWAY_TIMEOUT, message, details)

/** Answers with `HTTP_VERSION_NOT_SUPPORTED`. */
public open class HttpVersionNotSupportedResponse
@JvmOverloads
constructor(
    message: String = HttpStatus.HTTP_VERSION_NOT_SUPPORTED.message,
    details: Map<String, String> = emptyMap(),
) : HttpResponseException(HttpStatus.HTTP_VERSION_NOT_SUPPORTED, message, details)

/** Answers with `INSUFFICIENT_STORAGE`. */
public open class InsufficientStorageResponse
@JvmOverloads
constructor(message: String = HttpStatus.INSUFFICIENT_STORAGE.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.INSUFFICIENT_STORAGE, message, details)

/** Answers with `LOOP_DETECTED`. */
public open class LoopDetectedResponse
@JvmOverloads
constructor(message: String = HttpStatus.LOOP_DETECTED.message, details: Map<String, String> = emptyMap()) :
    HttpResponseException(HttpStatus.LOOP_DETECTED, message, details)

/** Answers with `NETWORK_AUTHENTICATION_REQUIRED`. */
public open class NetworkAuthenticationRequiredResponse
@JvmOverloads
constructor(
    message: String = HttpStatus.NETWORK_AUTHENTICATION_REQUIRED.message,
    details: Map<String, String> = emptyMap(),
) : HttpResponseException(HttpStatus.NETWORK_AUTHENTICATION_REQUIRED, message, details)

/** Thrown when no endpoint matches the request. */
public class EndpointNotFound(method: HandlerType, path: String) :
    NotFoundResponse("Endpoint ${method.name} $path not found")
