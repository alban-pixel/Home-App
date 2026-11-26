package io.github.albanpixel.tuya.protocol

import java.io.IOException

/** The frame cannot be authenticated or decrypted with the given key (bad key, HMAC, GCM tag or padding). */
class DecryptionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The bytes are not a valid Tuya frame (truncated, bad prefix/suffix/CRC/length, unknown command). */
class MalformedMessageException(message: String, cause: Throwable? = null) : IOException(message, cause)
