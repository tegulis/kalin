@file:Suppress("ktlint")
package gg.jte.generated.ondemand
@Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER")
internal class JtegreetingGenerated {
companion object {
	@JvmField val JTE_NAME = "greeting.kte"
	@JvmField val JTE_LINE_INFO = intArrayOf(0,0,0,0,0,0,0,1,1,1,1,2,2,2,0,0,0,0,0)
	@JvmStatic fun render(jteOutput:gg.jte.html.HtmlTemplateOutput, jteHtmlInterceptor:gg.jte.html.HtmlInterceptor?, name:String) {
		jteOutput.writeContent("<p>Kotlin template for ")
		jteOutput.setContext("p", null)
		jteOutput.writeUserContent(name)
		jteOutput.writeContent("</p>\n")
	}
	@JvmStatic fun renderMap(jteOutput:gg.jte.html.HtmlTemplateOutput, jteHtmlInterceptor:gg.jte.html.HtmlInterceptor?, params:Map<String, Any?>) {
		val name = params["name"] as String
		render(jteOutput, jteHtmlInterceptor, name);
	}
}
}
