"""Convert the project's SVG icon subset to Android VectorDrawable, without rasterizing.

Run from any directory: python graphics/tools/svg_to_vector.py
Supports path, circle, rect, groups and translate/scale transforms. Unknown SVG
elements fail explicitly to avoid silently producing incomplete icons.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)
def attr(name): return "{" + ANDROID + "}" + name
def number(value): return f"{float(value):g}"

def convert(source, destination):
    svg = ET.parse(source).getroot()
    x, y, width, height = map(float, svg.attrib["viewBox"].split())
    if (x, y) != (0, 0): raise ValueError("Only zero-origin viewBox is supported")
    vector = ET.Element("vector", {
        attr("width"): f"{width:g}dp", attr("height"): f"{height:g}dp",
        attr("viewportWidth"): number(width), attr("viewportHeight"): number(height),
    })
    def walk(element, parent, inherited):
        kind = element.tag.rsplit("}", 1)[-1]
        if kind in ("title", "desc"): return
        if kind not in ("svg", "g", "path", "circle", "rect"):
            raise ValueError(f"Unsupported element: {kind}")
        style = dict(inherited)
        style.update({key:value for key,value in element.attrib.items() if key != "transform"})
        for declaration in element.get("style", "").split(";"):
            if ":" in declaration:
                key,value = declaration.split(":",1); style[key.strip()] = value.strip()
        transform = element.get("transform", "")
        for match in re.finditer(r"(translate|scale)\(([^)]+)\)", transform):
            operation = match[1]; values = re.split(r"[\s,]+", match[2].strip())
            if not 1 <= len(values) <= 2: raise ValueError("Invalid transform")
            second = values[1] if len(values) == 2 else ("0" if operation == "translate" else values[0])
            parent = ET.SubElement(parent, "group", {attr(operation+"X"):number(values[0]), attr(operation+"Y"):number(second)})
        if re.sub(r"(translate|scale)\([^)]+\)", "", transform).strip():
            raise ValueError(f"Unsupported transform: {transform}")
        def color(value):
            if value == "none": return "#00000000"
            return style.get("color", "#000000") if value == "currentColor" else value
        if kind in ("path", "circle", "rect"):
            if kind == "path": data = element.attrib["d"]
            elif kind == "circle":
                cx,cy,r = (float(element.attrib[key]) for key in ("cx","cy","r"))
                data = f"M{cx-r:g},{cy:g} a{r:g},{r:g} 0,1 0,{2*r:g},0 a{r:g},{r:g} 0,1 0,{-2*r:g},0"
            else:
                if "rx" in element.attrib or "ry" in element.attrib: raise ValueError("Rounded rectangles need a path")
                rx,ry = (float(element.get(key,"0")) for key in ("x","y"))
                rw,rh = (float(element.attrib[key]) for key in ("width","height"))
                data = f"M{rx:g},{ry:g} h{rw:g} v{rh:g} h{-rw:g} Z"
            values = {attr("pathData"):data, attr("fillColor"):color(style.get("fill","#000000"))}
            for svgkey,xmlkey in [("stroke","strokeColor"),("stroke-width","strokeWidth"),
                    ("stroke-linecap","strokeLineCap"),("stroke-linejoin","strokeLineJoin"),
                    ("stroke-miterlimit","strokeMiterLimit"),("fill-opacity","fillAlpha"),("stroke-opacity","strokeAlpha")]:
                if svgkey in style: values[attr(xmlkey)] = color(style[svgkey]) if svgkey == "stroke" else style[svgkey]
            ET.SubElement(parent,"path",values)
        for child in element: walk(child,parent,style)
    walk(svg,vector,{})
    ET.indent(vector,space="    ")
    ET.ElementTree(vector).write(destination,encoding="utf-8",xml_declaration=True)

def main():
    graphics = Path(__file__).resolve().parents[1]
    # These SVGs use an alpha mask and are rendered natively by CatGirlIcon/KotikiIcon.
    sources = [p for p in sorted((graphics/"icons").glob("*.svg")) if p.stem not in {"cat-girl","pair"}]
    destination = graphics/"android/drawable"
    for source in sources:
        convert(source,destination/("ic_"+source.stem.replace("-","_")+".xml"))
    branding=sorted((graphics/"branding").glob("*.svg"))
    for source in branding:
        convert(source,destination/("ic_brand_"+source.stem.replace("-","_")+".xml"))
    print(f"Converted {len(sources)} SVG icons and {len(branding)} brand vectors.")

if __name__ == "__main__": main()
