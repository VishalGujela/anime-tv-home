import AppKit

// usage: icons <svg dir> <out dir> <map file: "package icon" per line>
// Renders each Lawnicons SVG as a white-on-transparent 144px PNG named after the package.
let args = CommandLine.arguments
let svgDir = args[1], outDir = args[2]
let map = try! String(contentsOfFile: args[3], encoding: .utf8)
let size = 144

for line in map.split(separator: "\n") {
    let parts = line.split(separator: " ").map(String.init)
    guard parts.count == 2, let svg = NSImage(contentsOfFile: "\(svgDir)/\(parts[1]).svg") else {
        print("skip \(line)"); continue
    }
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let r = NSRect(x: 0, y: 0, width: size, height: size)
    svg.draw(in: r)
    NSColor.white.set()
    r.fill(using: .sourceAtop)
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "\(outDir)/\(parts[0]).png"))
    print("ok \(parts[0]) <- \(parts[1])")
}
