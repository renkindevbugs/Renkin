package com.kaanelloed.iconeration

import android.graphics.Color

class ColorResource {
    private lateinit var currentColor: Color

    fun parse(color: String) {
        if (color == "none")
            currentColor = Color.valueOf(Color.TRANSPARENT)

        if (color.startsWith("#")) {
            parseRaw(color)
        }

        if (color.startsWith("@")) {
            parseResource(color)
        }

        if (color.startsWith("rgb")) {
            parseRGB(color)
        }

        if (color.startsWith("rgba")) {
            parseRBGA(color)
        }
    }

    fun fromInt(color: Int) {
        currentColor = intToColor(color)
    }

    override fun toString(): String {
        var hex = "#" + Integer.toHexString(currentColor.toArgb())
        if (hex.length == 2)
            hex = "#" + hex[1].toString().repeat(8)

        return hex
    }

    fun toHexRBGString(): String {
        return String.format(
            "#%02x%02x%02x", (currentColor.red() * 255).toInt(), (currentColor.green() * 255).toInt(), (currentColor.blue() * 255).toInt()
        )
    }

    fun toRGBString(): String{
        return "rgb(${currentColor.red()}, ${currentColor.green()}, ${currentColor.blue()})"
    }

    fun toRGBAString(): String{
        return "rgba(${currentColor.red()}, ${currentColor.green()}, ${currentColor.blue()}, ${currentColor.alpha()})"
    }

    private fun parseRaw(color: String) {
        var newColor = color

        if (color.length == 2) {
            newColor = "#" + color[1].toString().repeat(8)
        }

        if (color.length == 4) {
            newColor = "#FF" + color[1] + color[1] + color[2] + color[2] + color[3] + color[3]
        }

        if (color.length == 5) {
            newColor = "#" + color[1] + color[1] + color[2] + color[2] + color[3] + color[3] + color[4] + color[4]
        }

        currentColor = stringToColor(newColor)
    }

    private fun parseResource(color: String) {
        //TODO
        currentColor = intToColor(Color.BLACK)
    }

    private fun parseRGB(color: String) {
        val rgb = color.substring(4, color.length - 1)

        val elements = rgb.split(",")

        val red = elements[0].toFloat()
        val green = elements[1].toFloat()
        val blue = elements[2].toFloat()

        currentColor = Color.valueOf(red, green, blue)
    }

    private fun parseRBGA(color: String) {
        val rgb = color.substring(4, color.length - 2)

        val elements = rgb.split(",")

        val red = elements[0].toFloat()
        val green = elements[1].toFloat()
        val blue = elements[2].toFloat()
        val alpha = elements[3].toFloat()

        currentColor = Color.valueOf(red, green, blue, alpha)
    }

    private fun stringToColor(color: String): Color {
        return Color.valueOf(Color.parseColor(color))
    }

    private fun intToColor(color: Int): Color {
        return Color.valueOf(color)
    }
}