"""Design preview using bundled font + native JVM layout export. Not a device screenshot."""
import argparse
import json
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / 'app/src/main/res/font/diary_serif.ttf'
QA = ROOT / 'docs/qa'
SCALE = 3
WIDTH = 344
SAMPLE = [('松弛', 1., 'calm'), ('期待', .94, 'hope'), ('迷茫', .87, 'sadness'),
          ('平静', .82, 'calm'), ('温暖', .73, 'joy'), ('成长', .64, 'neutral'),
          ('安心', .58, 'calm'), ('想念', .51, 'sadness'), ('散步', .44, 'neutral'),
          ('咖啡', .38, 'neutral'), ('努力', .3, 'neutral'), ('微风', .21, 'neutral')]


def font(size):
    return ImageFont.truetype(str(FONT), round(size * SCALE))


def measurements():
    words = []
    for text, weight, category in SAMPLE:
        size = 14 + (min(26, max(20, WIDTH / 14.3)) - 14) * weight ** 1.3
        words.append(dict(text=text, weight=weight, category=category,
            width=float(font(size).getlength(text) / SCALE + 24), height=size * 1.22 + 18, font=size))
    QA.mkdir(parents=True, exist_ok=True)
    (QA / 'diary-envelope-measurements.json').write_text(json.dumps(dict(width=WIDTH, words=words), ensure_ascii=False, indent=2), encoding='utf-8')


def colors(category, index):
    if category in ('joy', 'hope'): return '#fcedbc', '#627a46'
    if category == 'calm': return '#dcefe4', '#4d806a'
    if category in ('sadness', 'anxiety'): return '#e8ddf0', '#77638c'
    if category in ('fatigue', 'anger'): return '#e0e9ee', '#617c89'
    return [('#fffcf2', '#687f55'), ('#f5e0df', '#747c5b'), ('#ddf0ec', '#5b8572'), ('#faf0d0', '#6d8150')][index % 4]


def pt(x, y): return round(x*SCALE), round(y*SCALE)


def centered(draw, text, y, size, fill):
    draw.text((180*SCALE, y*SCALE), text, font=font(size), fill=fill, anchor='mt')


def render(closed=False):
    geometry = json.loads((ROOT/'app/build/outputs/diary-envelope/geometry.json').read_text(encoding='utf-8'))
    cw = geometry['width']; ch = 0 if closed else geometry['height']
    title = 80; eh = 360*.40; footer = 76
    height = math.ceil(title + ch + eh + footer)
    w, h = 360*SCALE, height*SCALE
    y, x = np.mgrid[0:h, 0:w]; x=x/w; y=y/h
    base = np.empty((h,w,3));base[:]=[252,251,246]
    for cx,cy,rgb,r in [(1.02,.28,[216,240,220],.85),(.28,.91,[221,241,227],1.12),(.2,.16,[255,255,255],.8)]:
        distance=np.sqrt((x-cx)**2+((y-cy)*height/360)**2)
        alpha=np.maximum(0,1-distance/r)[...,None]
        base=base*(1-alpha)+np.array(rgb)*alpha
    rng=np.random.default_rng(481);base+=rng.normal(0,.5,(h,w,1))
    im=Image.fromarray(np.clip(base,0,255).astype('uint8')).convert('RGBA')
    d=ImageDraw.Draw(im)
    centered(d,'情绪关键词回顾',16,23,'#4c8052')
    centered(d,'把重要的感受轻轻留下',52,13,'#4c8052')
    if not closed:
        layer=Image.new('RGBA',im.size);draw=ImageDraw.Draw(layer)
        origin=(180,title+ch+eh*.70)
        for i,word in enumerate(geometry['words']):
            end=(word['x']+8,word['y']+title)
            bend=-1 if i%2==0 else 1
            c1=(origin[0]+bend*cw*.14,title+ch*.72)
            c2=(end[0]+bend*cw*.14*.38,end[1]+cw*.14)
            previous=None
            for step in range(180):
                t=step/179
                at=tuple((1-t)**3*origin[a]+3*(1-t)**2*t*c1[a]+3*(1-t)*t*t*c2[a]+t**3*end[a] for a in (0,1))
                if previous and step%6<3: draw.line([pt(*previous),pt(*at)],fill=(145,199,150,110),width=2)
                previous=at
        leaves=[(.08,.66),(.91,.46),(.78,.03),(.19,.14),(.88,.85),(.35,.90),(.53,.61)]
        for i,(lx,ly) in enumerate(leaves):
            leaf=Image.new('RGBA',(70,90));ld=ImageDraw.Draw(leaf)
            ld.ellipse((20,5,50,75),fill=(150,201,137,150));ld.line((35,12,35,74),fill=(125,180,120,100),width=2)
            leaf=leaf.rotate(-28 if i%2==0 else 35,expand=True)
            layer.alpha_composite(leaf,(round((8+cw*lx)*SCALE-leaf.width/2),round((title+ch*ly)*SCALE-leaf.height/2)))
        for i,(lx,ly) in enumerate([(.1,.38),(.9,.31),(.67,.14),(.07,.83),(.94,.72)]):
            px,py=pt(8+cw*lx,title+ch*ly);r=7 if i%2==0 else 5
            draw.ellipse((px-r,py-r,px+r,py+r),fill=(243,199,212,170) if i%2==0 else (184,229,216,170))
        im=Image.alpha_composite(im,layer)
    # White paper envelope with the same normalized folds as native Canvas.
    ey=title+ch;left=180-(eh/.69)*.38;right=360-left;top=ey+eh*.31;bottom=ey+eh*.93;mouth=ey+eh*.70
    shadow=Image.new('RGBA',im.size);sd=ImageDraw.Draw(shadow)
    sd.rounded_rectangle([pt(left,top+5),pt(right,bottom+5)],radius=12,fill=(85,131,80,65))
    im=Image.alpha_composite(im,shadow.filter(ImageFilter.GaussianBlur(10*SCALE)))
    paper=Image.new('RGBA',im.size);pd=ImageDraw.Draw(paper)
    pd.polygon([pt(left,top),pt(180,top if closed else ey+eh*.04),pt(right,top),pt(right,bottom),pt(left,bottom)],fill='#fafdf5')
    pd.polygon([pt(left,top),pt(180,top if closed else ey+eh*.16),pt(right,top),pt(180,mouth)],fill='#dae9d6')
    pd.polygon([pt(left,top),pt(187,mouth),pt(left,bottom)],fill='#fbfcf7')
    pd.polygon([pt(right,top),pt(173,mouth),pt(right,bottom)],fill='#f4f8ee')
    pd.polygon([pt(left,bottom),pt(180,ey+eh*.56),pt(right,bottom)],fill='#fdfefa')
    pd.line([pt(left,bottom),pt(180,ey+eh*.56),pt(right,bottom)],fill='#d8e6d4',width=2)
    if closed: pd.polygon([pt(left,top),pt(right,top),pt(180,mouth)],fill='#f0f5e9')
    # Clip fine deterministic paper fibers to the native envelope silhouette.
    paper_mask=paper.getchannel('A')
    fibers=Image.new('RGBA',im.size);fd=ImageDraw.Draw(fibers)
    for _ in range(1600):
        px=float(rng.uniform(left,right));py=float(rng.uniform(ey,bottom))
        fd.line([pt(px,py),pt(px+1,py+.2)],fill=(138,160,130,12),width=1)
    fibers.putalpha(Image.fromarray((np.asarray(fibers.getchannel('A'),dtype=np.uint16)*np.asarray(paper_mask,dtype=np.uint16)//255).astype('uint8')))
    paper=Image.alpha_composite(paper,fibers)
    im=Image.alpha_composite(im,paper)
    if not closed:
        for i,word in enumerate(geometry['words']):
            bw,bh=word['width']-8,word['height']-8
            tile=Image.new('RGBA',pt(bw+32,bh+32));td=ImageDraw.Draw(tile)
            paperColor,ink=colors(word['category'],i)
            sh=Image.new('RGBA',tile.size);ssd=ImageDraw.Draw(sh)
            ssd.rounded_rectangle([pt(16,19),pt(16+bw,19+bh)],radius=12*SCALE,fill=(93,130,81,65))
            tile=Image.alpha_composite(tile,sh.filter(ImageFilter.GaussianBlur(4*SCALE)));td=ImageDraw.Draw(tile)
            td.rounded_rectangle([pt(16,16),pt(16+bw,16+bh)],radius=12*SCALE,fill=paperColor,outline=(255,255,255,230),width=3)
            td.text(pt(16+bw/2,16+bh/2),word['text'],font=font(word['font']),fill=ink,anchor='mm')
            tile=tile.rotate(-word['angle'],resample=Image.Resampling.BICUBIC,expand=True)
            im.alpha_composite(tile,(round((word['x']+8)*SCALE-tile.width/2),round((word['y']+title)*SCALE-tile.height/2)))
    d=ImageDraw.Draw(im)
    centered(d,'轻触，打开这篇日记' if closed else '轻触收起信笺',ey+eh*.79,11,'#7c947d')
    centered(d,'整理关键词' if closed else '更新关键词     再看一次     分析依据',ey+eh+14,12,'#4c8052')
    centered(d,'词语来自这篇文字 · 大小表示相对重要程度',ey+eh+48,9,'#7c947d')
    centered(d,'静态设计预览 · 示例文字 · 非设备截图',height-19,8,'#99a397')
    name='diary-envelope-closed.png' if closed else 'diary-envelope-expanded.png'
    target=QA/name;im.convert('RGB').save(target);print(target)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--measure',action='store_true');args=parser.parse_args()
    if args.measure: measurements()
    else: render();render(True)
