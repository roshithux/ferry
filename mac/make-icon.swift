// Draws Ferry's 1024px app icon: the ⇄ symbol on Ferry blue (same blue as the Android icon).
// Usage: swift make-icon.swift <output.png>
import AppKit

let canvas: CGFloat = 1024
let inset: CGFloat = 100            // macOS icon grid: an 824px rounded square centred on the canvas
let blue = NSColor(srgbRed: 0x25 / 255, green: 0x63 / 255, blue: 0xEB / 255, alpha: 1)

let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(canvas), pixelsHigh: Int(canvas),
                           bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                           colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

let tile = NSRect(x: inset, y: inset, width: canvas - inset * 2, height: canvas - inset * 2)
blue.setFill()
NSBezierPath(roundedRect: tile, xRadius: 185, yRadius: 185).fill()

let config = NSImage.SymbolConfiguration(pointSize: 420, weight: .semibold)
    .applying(NSImage.SymbolConfiguration(paletteColors: [.white]))
let symbol = NSImage(systemSymbolName: "arrow.left.arrow.right", accessibilityDescription: nil)!
    .withSymbolConfiguration(config)!
let size = symbol.size
symbol.draw(in: NSRect(x: (canvas - size.width) / 2, y: (canvas - size.height) / 2, width: size.width, height: size.height))

NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
