import org.xmtp.android.library.libxmtp.DecodedMessage
import org.xmtp.android.library.libxmtp.MessageDeliveryStatus

fun test(msg: DecodedMessage) {
    val status = msg.deliveryStatus
    val isPublished = status == MessageDeliveryStatus.PUBLISHED
}
