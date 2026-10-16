@param String name
@param java.util.List<String> items
<h1>Hello ${name}!</h1>
<ul>@for(var item : items)<li>${item}</li>@endfor</ul>
