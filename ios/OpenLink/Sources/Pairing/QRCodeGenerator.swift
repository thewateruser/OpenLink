//
//  QRCodeGenerator.swift
//  OpenLink (parent app)
//
//  Generates a QR code image from a string using Apple's built-in CoreImage
//  CIFilter QR generator — no third-party dependency needed.
//

import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit

enum QRCodeGenerator {
    /// Renders `string` as a QR code UIImage, upscaled with nearest-neighbor
    /// scaling (so edges stay crisp) by `scale`.
    static func image(from string: String, scale: CGFloat = 10) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage else { return nil }

        let transform = CGAffineTransform(scaleX: scale, y: scale)
        let scaledImage = outputImage.transformed(by: transform)

        guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
