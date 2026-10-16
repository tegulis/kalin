@import net.tegulis.kalin.examples.Page
@param Page page
<!DOCTYPE html>
<html lang="en">
<head><meta charset="utf-8"><title>${page.getTitle()}</title></head>
<body>
<h1>${page.getTitle()}</h1>
<ul>
@for(String item : page.getItems())
    <li>${item}</li>
@endfor
</ul>
</body>
</html>
