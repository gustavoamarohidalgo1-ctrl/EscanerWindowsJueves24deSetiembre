#!/usr/bin/env swift

import AppKit
import Foundation

let arguments = CommandLine.arguments
guard arguments.count == 2 else {
    FileHandle.standardError.write(Data("Uso: render-play-icon.swift <salida.png>\n".utf8))
    exit(64)
}

let size = NSSize(width: 512, height: 512)
guard let bitmap = NSBitmapImageRep(
    bitmapDataPlanes: nil,
    pixelsWide: Int(size.width),
    pixelsHigh: Int(size.height),
    bitsPerSample: 8,
    samplesPerPixel: 4,
    hasAlpha: true,
    isPlanar: false,
    colorSpaceName: .deviceRGB,
    bytesPerRow: 0,
    bitsPerPixel: 0
) else {
    fatalError("No se pudo crear el lienzo")
}

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)

NSColor(
    calibratedRed: 54.0 / 255.0,
    green: 92.0 / 255.0,
    blue: 157.0 / 255.0,
    alpha: 1
).setFill()
NSBezierPath(rect: NSRect(origin: .zero, size: size)).fill()

NSColor.white.setFill()
NSBezierPath(rect: NSRect(x: 144, y: 112, width: 224, height: 288)).fill()

NSColor(
    calibratedRed: 54.0 / 255.0,
    green: 92.0 / 255.0,
    blue: 157.0 / 255.0,
    alpha: 1
).setFill()
[
    NSRect(x: 184, y: 312, width: 144, height: 24),
    NSRect(x: 184, y: 248, width: 144, height: 24),
    NSRect(x: 184, y: 184, width: 80, height: 24),
    NSRect(x: 288, y: 176, width: 40, height: 40),
].forEach { NSBezierPath(rect: $0).fill() }

NSGraphicsContext.restoreGraphicsState()

guard let data = bitmap.representation(using: .png, properties: [:]) else {
    fatalError("No se pudo codificar el PNG")
}
try data.write(to: URL(fileURLWithPath: arguments[1]), options: .atomic)
