"""Deterministic private video composition; no generative-video provider or public URL."""
import io
import json
import os
import subprocess
import tempfile
from pathlib import Path
import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFont, ImageOps

def run(args,timeout=300):
    result=subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-hide_banner','-loglevel','error','-nostdin',*args],capture_output=True,timeout=timeout)
    if result.returncode: raise RuntimeError('视频合成失败，请检查素材或重试')
    return result

def audio_duration(data):
    with tempfile.TemporaryDirectory(prefix='letter-probe-') as tmp:
        path=Path(tmp)/'audio'; path.write_bytes(data)
        result=subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-hide_banner','-i',str(path)],capture_output=True,timeout=20)
        import re
        match=re.search(rb'Duration: (\d+):(\d+):(\d+(?:\.\d+)?)',result.stderr)
        if not match: return 0
        h,m,s=map(float,match.groups()); return h*3600+m*60+s

def font(size,letter=False):
    candidates=([os.getenv('MEMORY_LETTER_FONT',''),'C:/Windows/Fonts/simkai.ttf'] if letter else [])+[
                os.getenv('MEMORY_CJK_FONT',''),'C:/Windows/Fonts/msyh.ttc','C:/Windows/Fonts/simhei.ttf',
                '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc']
    for p in candidates:
        if p and Path(p).is_file(): return ImageFont.truetype(p,size)
    raise RuntimeError('请配置 MEMORY_CJK_FONT 中文字体文件')

def lines(text,font,width):
    output=[]; line=''
    for char in text:
        if char=='\n': output.append(line); line=''; continue
        if font.getlength(line+char)>width: output.append(line); line=char
        else: line+=char
    if line: output.append(line)
    return output

def captions(payload):
    text=payload.get('segments') or [payload.get('text','')]+[d.get('text','') for d in payload.get('diaries',[])]
    chunks=[]
    for part in text:
        for i in range(0,len(part),100):
            if part[i:i+100].strip(): chunks.append(part[i:i+100])
    if len(chunks)>24: raise RuntimeError('文字太长，请整理为最多2400字再生成')
    return chunks or [payload['title']]

def paper_lines(text,typeface,width):
    """Chinese closing punctuation stays with the preceding character, never alone."""
    closing=set('，。！？；：、）》】」』”’…,.!?;:)]}%')
    result=[]; row=''
    for char in text:
        if char=='\n': result.append(row); row=''; continue
        if row and typeface.getlength(row+char)>width:
            if char in closing and len(row)>1:
                result.append(row[:-1]); row=row[-1]+char
            else: result.append(row); row=char
        else: row+=char
    if row: result.append(row)
    return result

def letter_layout(payload,photos,folder,narration=0):
    """A stationary paper frame with a full-height, non-truncated scrolling letter."""
    import math
    import random
    folder=Path(folder)
    paper=(255,252,243); ink=(69,61,53); width=540; line_height=76
    heading=font(38,letter=True); body=font(42,letter=True); small=font(19)
    # Keep paragraph boundaries instead of chopping a sentence every 100 characters.
    selected=payload.get('segments') or [payload.get('text','')]+[d.get('text','') for d in payload.get('diaries',[])]
    text='\n\n'.join(p.strip() for p in selected if p and p.strip()).replace('\r\n','\n').replace('\r','\n')
    if len(text)>2400: raise RuntimeError('信件文字过长，请先精简或整理后再生成')
    if not text: text='把此刻的声音，留给未来的自己。'
    rows=[]; y=42
    for paragraph in text.split('\n'):
        if not paragraph.strip(): y+=26; continue
        paragraph=paragraph.strip()
        indent='' if paragraph.endswith(('：',':')) else '　　'
        for line in paper_lines(indent+paragraph,body,width-12):
            rows.append((y,line)); y+=line_height
        y+=20
    photo_y=y+24
    photo_height=370
    content_end=photo_y+len(photos)*photo_height
    end_y=content_end+45
    title_rows=paper_lines(' '.join((payload.get('title') or '给未来的自己').split()) or '给未来的自己',heading,536)
    viewport_top=150+len(title_rows)*52+58
    viewport_height=(1156-viewport_top)//2*2
    scroll_height=max(viewport_height+80,end_y+140)
    distance=scroll_height-viewport_height
    intro,outro=3,5
    minimum=distance/55+intro+outro
    if minimum>180: raise RuntimeError('信纸内容较长，三分钟内滚动会太快；请精简正文或减少照片后再生成')
    total=min(180,max(18,minimum,len(text)/5.2+len(photos)*6+intro+outro,narration))
    canvas=Image.new('RGB',(720,1280),(228,219,201)); draw=ImageDraw.Draw(canvas)
    # Very light deterministic paper grain, with a soft layered shadow.
    for i in range(14,0,-1):
        draw.rounded_rectangle((43-i//3,46-i//4,681+i//2,1240+i//2),radius=17,fill=(222-i//3,212-i//3,193-i//3))
    draw.rounded_rectangle((40,40,676,1232),radius=14,fill=paper,outline=(235,228,214),width=1)
    rng=random.Random(17)
    for _ in range(4500):
        x=rng.randrange(48,668); yy=rng.randrange(48,1224)
        draw.point((x,yy),fill=(249,245,234))
    draw.text((88,83),'时 光 信 箱',font=small,fill=(153,132,105))
    draw.line((530,95,628,95),fill=(199,176,142),width=2)
    for i,line in enumerate(title_rows): draw.text((88,143+i*52),line,font=heading,fill=ink)
    draw.line((88,viewport_top-25,628,viewport_top-25),fill=(219,204,177),width=1)
    draw.text((88,1184),'此 刻 写 下  ·  未 来 重 逢',font=small,fill=(153,132,105))
    canvas.save(folder/'stationery.png')
    letter=Image.new('RGB',(width,scroll_height),paper); pen=ImageDraw.Draw(letter)
    for yy,line in rows:
        pen.line((0,yy+60,width,yy+60),fill=(234,227,212),width=1)
        pen.text((3,yy),line,font=body,fill=ink)
    for i,path in enumerate(photos):
        yy=photo_y+i*photo_height
        pen.rounded_rectangle((18,yy,width-18,yy+332),radius=5,fill=(255,255,252),outline=(225,215,194),width=1)
        with Image.open(path) as src:
            picture=ImageOps.contain(ImageOps.exif_transpose(src).convert('RGB'),(width-64,278))
            letter.paste(picture,((width-picture.width)//2,yy+16+(278-picture.height)//2))
        pen.text((38,yy+302),f'随信照片  {i+1:02d}',font=small,fill=(153,132,105))
    pen.text((width-210,end_y),'现在的我',font=font(30,letter=True),fill=(116,97,75))
    letter.save(folder/'letter-scroll.png')
    mask=Image.new('L',(width,viewport_height),255); md=ImageDraw.Draw(mask)
    for yy in range(viewport_height):
        alpha=min(1,yy/24,(viewport_height-1-yy)/32)
        if alpha<1: md.line((0,yy,width,yy),fill=max(0,round(255*alpha)))
    mask.save(folder/'letter-mask.png')
    return dict(duration=total,viewport_top=viewport_top,viewport_height=viewport_height,scroll_distance=distance,
                scroll_seconds=total-intro-outro,intro=intro,text=text,line_count=len(rows))

def render(payload,assets,destination):
    """A letter on real paper, slowly scrolling upward; original narration is retained."""
    destination=Path(destination); folder=destination.parent
    photos=[p for kind,p in assets if kind=='image']; voices=[p for kind,p in assets if kind=='audio']
    narration=audio_duration(voices[0].read_bytes()) if voices else 0
    layout=letter_layout(payload,photos,folder,narration)
    total=layout['duration']; visual=folder/'visual.mp4'
    offset=f"{layout['scroll_distance']}*clip((t-{layout['intro']})/{layout['scroll_seconds']},0,1)"
    filters=(f"[0:v]fps=24[paper];[1:v]fps=24,crop=540:{layout['viewport_height']}:0:'{offset}',format=rgba[body];"
             f"[2:v]fps=24,format=gray[mask];[body][mask]alphamerge[writing];"
             f"[paper][writing]overlay=88:{layout['viewport_top']},fade=t=in:st=0:d=0.4,fade=t=out:st={total-0.5}:d=0.5,format=yuv420p[v]")
    run(['-y','-loop','1','-framerate','1','-i',str(folder/'stationery.png'),
         '-loop','1','-framerate','1','-i',str(folder/'letter-scroll.png'),
         '-loop','1','-framerate','1','-i',str(folder/'letter-mask.png'),
         '-filter_complex',filters,'-map','[v]','-t',str(total),'-r','24','-c:v','libx264','-preset','veryfast','-threads','2',str(visual)],timeout=600)
    music=Path(__file__).resolve().parents[1]/'assets/audio/healing.ogg'
    args=['-y','-i',str(visual)]
    if voices: args+=['-i',str(voices[0])]
    if payload.get('music') and music.exists():
        args+=['-stream_loop','-1','-i',str(music)]
        if voices: args+=['-filter_complex','[1:a]apad[v];[2:a]volume=0.12[m];[v][m]amix=inputs=2:duration=first[a]','-map','0:v','-map','[a]']
        else: args+=['-filter_complex','[1:a]volume=0.25[a]','-map','0:v','-map','[a]']
    elif voices: args+=['-filter_complex','[1:a]apad[a]','-map','0:v','-map','[a]']
    else: args+=['-f','lavfi','-i','anullsrc=channel_layout=stereo:sample_rate=44100','-map','0:v','-map','1:a']
    run(args+['-t',str(total),'-c:v','copy','-c:a','aac','-movflags','+faststart',str(destination)])

def join_timelines(original,response,destination):
    # Preserve both complete versions; label time lines rather than inventing a narrative.
    folder=Path(destination).parent; f=font(32)
    normalized=[]
    for i,(source,label) in enumerate([(original,'当时的我'),(response,'现在的我')]):
        banner=Image.new('RGBA',(720,85),(249,253,247,240)); ImageDraw.Draw(banner).text((30,20),label,font=f,fill=(44,62,80))
        image=folder/f'label{i}.png'; banner.save(image); clip=folder/f'part{i}.mp4'
        duration=audio_duration(Path(source).read_bytes())
        speed=max(1,duration/89.8)
        filters=f'[0:v]setpts=(PTS-STARTPTS)/{speed}[s];[s][1:v]overlay=0:0[v];[0:a]atempo={speed}[a]'
        run(['-y','-i',str(source),'-i',str(image),'-filter_complex',filters,'-map','[v]','-map','[a]','-c:v','libx264','-preset','veryfast','-threads','2','-c:a','aac',str(clip)])
        normalized.append(clip)
    listing=folder/'dual.txt'; listing.write_text('\n'.join("file '"+p.name+"'" for p in normalized),encoding='utf-8')
    run(['-y','-f','concat','-safe','0','-i',str(listing),'-c','copy','-movflags','+faststart',str(destination)])
