package gg.jte.generated.ondemand;
@SuppressWarnings("unchecked")
@javax.annotation.processing.Generated("gg.jte.TemplateEngine")
public final class JtehelloGenerated {
	public static final String JTE_NAME = "hello.jte";
	public static final int[] JTE_LINE_INFO = {0,0,0,0,0,0,2,2,2,2,3,3,3,3,3,3,3,4,4,4,0,1,1,1,1};
	public static void render(gg.jte.html.HtmlTemplateOutput jteOutput, gg.jte.html.HtmlInterceptor jteHtmlInterceptor, String name, java.util.List<String> items) {
		jteOutput.writeContent("<h1>Hello ");
		jteOutput.setContext("h1", null);
		jteOutput.writeUserContent(name);
		jteOutput.writeContent("!</h1>\n<ul>");
		for (var item : items) {
			jteOutput.writeContent("<li>");
			jteOutput.setContext("li", null);
			jteOutput.writeUserContent(item);
			jteOutput.writeContent("</li>");
		}
		jteOutput.writeContent("</ul>\n");
	}
	public static void renderMap(gg.jte.html.HtmlTemplateOutput jteOutput, gg.jte.html.HtmlInterceptor jteHtmlInterceptor, java.util.Map<String, Object> params) {
		String name = (String)params.get("name");
		java.util.List<String> items = (java.util.List<String>)params.get("items");
		render(jteOutput, jteHtmlInterceptor, name, items);
	}
}
