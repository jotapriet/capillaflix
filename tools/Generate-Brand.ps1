# Regenerates Android vector assets and the preview from the same paths.
Add-Type -AssemblyName System.Drawing
$root = Split-Path $PSScriptRoot -Parent
$res = Join-Path $root 'app/src/main/res/drawable'
$gold = '#D9B777'
$dark = '#101421'
$shapes = @(
    @{ d='M54,28 C44,23 60,19 52,13'; stroke=$gold; width=2.4 },
    @{ d='M62,31 C54,25 69,22 62,17'; stroke=$gold; width=1.6 },
    @{ d='M49,37 C49,34 51,32 54,32 C57,32 59,34 59,37'; stroke=$gold; width=2.2 },
    @{ d='M49,38 L37,65 M54,39 L54,52 M59,38 L71,65'; stroke=$gold; width=1.8 },
    @{ d='M54,51 L54,55'; stroke=$gold; width=2.4 },
    @{ d='M42,62 C44,56 48,54 54,54 C60,54 64,56 66,62 Z'; fill=$gold },
    @{ d='M49,59 L49,61 M54,58 L54,61 M59,59 L59,61'; stroke=$dark; width=1.4 },
    @{ d='M36,66 L72,66'; stroke=$gold; width=2.8 },
    @{ d='M39,70 L69,70 C67,78 62,82 54,82 C46,82 41,78 39,70 Z'; fill=$gold },
    @{ d='M50,82 L50,85 L46,88 L62,88 L58,85 L58,82 Z'; fill=$gold }
)
# Keep the entire symbol inside the adaptive icon safe circle (diameter 66).
$symbolGroup = '<group android:scaleX="0.75" android:scaleY="0.75" android:pivotX="54" android:pivotY="54">'
function ShapeXml($shape) {
    $attrs = 'android:fillColor="' + $(if($shape.fill){$shape.fill}else{'#00000000'}) + '" android:pathData="' + $shape.d + '"'
    if($shape.stroke){$attrs += ' android:strokeColor="' + $shape.stroke + '" android:strokeWidth="' + $shape.width + '" android:strokeLineCap="round" android:strokeLineJoin="round"'}
    '    <path ' + $attrs + ' />'
}
function VectorXml($width,$height,$content) {
    '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="' + $width + 'dp" android:height="' + $height + 'dp" android:viewportWidth="' + $width + '" android:viewportHeight="' + $height + '">' + "`n" + $content + "`n</vector>"
}
$symbol = ($shapes | ForEach-Object { ShapeXml $_ }) -join "`n"
VectorXml 108 108 ($symbolGroup + "`n" + $symbol + "`n</group>") | Set-Content (Join-Path $res 'ic_launcher_foreground.xml')
VectorXml 108 108 $symbol | Set-Content (Join-Path $res 'ic_brand.xml')
VectorXml 108 108 ('<path android:fillColor="' + $dark + '" android:pathData="M0,0 L108,0 L108,108 L0,108 Z" />') | Set-Content (Join-Path $res 'ic_launcher_background.xml')

function DrawingPath($d) {
    $path = [Drawing.Drawing2D.GraphicsPath]::new()
    $tokens = [regex]::Matches($d,'[MLCZ]|-?\d+(?:\.\d+)?') | ForEach-Object Value
    $i=0; $x=0; $y=0
    while($i -lt $tokens.Count){
        $command=$tokens[$i++]
        switch($command){
            'M' { $x=[float]$tokens[$i++];$y=[float]$tokens[$i++];$path.StartFigure() }
            'L' { $nx=[float]$tokens[$i++];$ny=[float]$tokens[$i++];$path.AddLine($x,$y,$nx,$ny);$x=$nx;$y=$ny }
            'C' { $a=[float]$tokens[$i++];$b=[float]$tokens[$i++];$c=[float]$tokens[$i++];$e=[float]$tokens[$i++];$nx=[float]$tokens[$i++];$ny=[float]$tokens[$i++];$path.AddBezier($x,$y,$a,$b,$c,$e,$nx,$ny);$x=$nx;$y=$ny }
            'Z' { $path.CloseFigure() }
        }
    }
    return ,$path
}
function TextPath($text,$size,$x,$y,$style) {
    $path=[Drawing.Drawing2D.GraphicsPath]::new()
    $family=[Drawing.FontFamily]::new('Segoe UI')
    $path.AddString($text,$family,[int]$style,[float]$size,[Drawing.PointF]::new($x,$y),[Drawing.StringFormat]::GenericTypographic)
    $family.Dispose()
    return ,$path
}
function PathData($path) {
    $parts=[Collections.Generic.List[string]]::new();$points=$path.PathPoints;$types=$path.PathTypes
    $culture=[Globalization.CultureInfo]::InvariantCulture
    function Point($p){$p.X.ToString('0.###',$culture)+','+$p.Y.ToString('0.###',$culture)}
    for($i=0;$i -lt $points.Length;$i++){
        $type=$types[$i] -band 7
        if($type -eq 0){$parts.Add('M'+(Point $points[$i]))}
        elseif($type -eq 1){$parts.Add('L'+(Point $points[$i]))}
        elseif($type -eq 3){$parts.Add('C'+(Point $points[$i])+' '+(Point $points[$i+1])+' '+(Point $points[$i+2]));$i+=2}
        if($types[$i] -band 128){$parts.Add('Z')}
    }
    $parts -join ' '
}
$capilla=TextPath 'CAPILLA' 22 108 67 ([Drawing.FontStyle]::Bold)
$flix=TextPath 'FLIX' 22 ($capilla.GetBounds().Right+1) 67 ([Drawing.FontStyle]::Regular)
$subtitle=TextPath 'SEVILLA / SEMANA SANTA' 7.4 110 102 ([Drawing.FontStyle]::Regular)
$background='<path android:fillColor="'+$dark+'" android:pathData="M0,0 L320,0 L320,180 L0,180 Z" />'
# Emblem centred on the left; typography converted to outlines for consistent rendering.
$banner=$background + "`n" + '<group android:translateX="9" android:translateY="30" android:scaleX="1.1" android:scaleY="1.1">' + "`n" + $symbol + "`n</group>`n"
$banner += (ShapeXml @{d=(PathData $capilla);fill='#F5F2EC'}) + "`n"
$banner += (ShapeXml @{d=(PathData $flix);fill=$gold}) + "`n"
$banner += (ShapeXml @{d=(PathData $subtitle);fill='#A4ADBE'})
VectorXml 320 180 $banner | Set-Content (Join-Path $res 'tv_banner.xml')

# Render previews directly from the Android vector definitions.
function RenderVector($file,$output,$width,$height) {
    [xml]$doc=Get-Content $file -Raw
    $bitmap=[Drawing.Bitmap]::new($width,$height)
    $graphics=[Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode=[Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([Drawing.ColorTranslator]::FromHtml($dark))
    $graphics.ScaleTransform($width/[float]$doc.vector.GetAttribute('android:viewportWidth'),$height/[float]$doc.vector.GetAttribute('android:viewportHeight'))
    function PaintNode($node){
        foreach($child in $node.ChildNodes){
            if($child.Name -eq 'group'){
                $state=$graphics.Save()
                $tx=$child.GetAttribute('android:translateX');$ty=$child.GetAttribute('android:translateY')
                $sx=$child.GetAttribute('android:scaleX');$sy=$child.GetAttribute('android:scaleY')
                $px=$child.GetAttribute('android:pivotX');$py=$child.GetAttribute('android:pivotY')
                $graphics.TranslateTransform([float]$tx+[float]$px,[float]$ty+[float]$py)
                if($sx){$graphics.ScaleTransform([float]$sx,[float]$sy)}
                $graphics.TranslateTransform(-[float]$px,-[float]$py)
                PaintNode $child
                $graphics.Restore($state)
            }elseif($child.Name -eq 'path'){
                $path=DrawingPath $child.GetAttribute('android:pathData')
                $fill=$child.GetAttribute('android:fillColor')
                if($fill -and $fill -ne '#00000000'){$brush=[Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($fill));$graphics.FillPath($brush,$path);$brush.Dispose()}
                $stroke=$child.GetAttribute('android:strokeColor')
                if($stroke){$pen=[Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml($stroke),[float]$child.GetAttribute('android:strokeWidth'));$pen.StartCap=[Drawing.Drawing2D.LineCap]::Round;$pen.EndCap=[Drawing.Drawing2D.LineCap]::Round;$pen.LineJoin=[Drawing.Drawing2D.LineJoin]::Round;$graphics.DrawPath($pen,$path);$pen.Dispose()}
                $path.Dispose()
            }
        }
    }
    PaintNode $doc.vector
    $bitmap.Save($output,[Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose();$bitmap.Dispose()
}
$docs=Join-Path $root 'docs'
New-Item -ItemType Directory -Force $docs | Out-Null
RenderVector (Join-Path $res 'ic_launcher_foreground.xml') (Join-Path $docs 'logo-icon.png') 432 432
RenderVector (Join-Path $res 'tv_banner.xml') (Join-Path $docs 'logo-banner.png') 1280 720
$capilla.Dispose();$flix.Dispose();$subtitle.Dispose()
