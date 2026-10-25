import AppKit

// Renders the launcher's banner (320x180) and icon (96x96) into res/drawable-xhdpi.
func render(_ w: Int, _ h: Int, _ path: String, _ draw: (CGRect) -> Void) {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    draw(CGRect(x: 0, y: 0, width: w, height: h))
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}

func text(_ s: String, _ size: CGFloat, _ color: NSColor, _ rect: CGRect, bold: Bool = true) {
    let p = NSMutableParagraphStyle(); p.alignment = .center
    let attrs: [NSAttributedString.Key: Any] = [
        .font: bold ? NSFont.boldSystemFont(ofSize: size) : NSFont.systemFont(ofSize: size),
        .foregroundColor: color, .paragraphStyle: p, .kern: size * 0.12]
    let str = NSAttributedString(string: s, attributes: attrs)
    let hgt = str.size().height
    str.draw(in: CGRect(x: rect.minX, y: rect.midY - hgt / 2, width: rect.width, height: hgt))
}

let out = CommandLine.arguments[1]
let orange = NSColor(red: 1, green: 0.45, blue: 0.08, alpha: 1)

render(320, 180, out + "/banner.png") { r in
    NSGradient(starting: NSColor(white: 0.05, alpha: 1), ending: NSColor(red: 0.35, green: 0.12, blue: 0.02, alpha: 1))!
        .draw(in: r, angle: 0)
    text("ANIME HOME", 34, .white, CGRect(x: 0, y: 80, width: 320, height: 60))
    text("月  雷  油  渦  烏", 22, orange, CGRect(x: 0, y: 30, width: 320, height: 40), bold: false)
}

render(96, 96, out + "/icon.png") { r in
    let circle = NSBezierPath(ovalIn: r.insetBy(dx: 2, dy: 2))
    NSColor(white: 0.07, alpha: 1).setFill(); circle.fill()
    orange.setStroke(); circle.lineWidth = 4; circle.stroke()
    text("渦", 50, orange, r, bold: false)
}
