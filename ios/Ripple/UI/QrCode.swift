import UIKit
import CoreImage.CIFilterBuiltins

/// Offline QR rendering (Core Image, no camera): shows codes for another device's
/// *scanner app*. The Ripple apps deliberately have no camera permission — scanning
/// happens outside the app and the decoded text is pasted back in (docs/PAIRING.md).
enum QrCode {
    /// Encode `text` as a crisp black/white QR image. `pointSize` is the intended
    /// display width in points; rendering at 3x keeps modules sharp on retina.
    /// Returns nil if the content cannot be encoded (too long for a QR).
    static func image(from text: String, pointSize: CGFloat = 240) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let side = max(output.extent.width, output.extent.height)
        guard side > 0 else { return nil }
        let scale = max(1, (pointSize * 3 / side).rounded(.down))
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
