/* Canvas backend. Glyphs and golden cursor are fetched from Navigator's own resources. */
'use strict';
function paintHudAndroid(bitmap,commands){
 const canvas=Java.use('android.graphics.Canvas').$new(bitmap),paint=Java.use('android.graphics.Paint').$new(3);
 const Style=Java.use('android.graphics.Paint$Style');
 const context=Java.use('android.app.ActivityThread').currentApplication();
 paint.setStrokeCap(Java.use('android.graphics.Paint$Cap').ROUND.value);paint.setStrokeJoin(Java.use('android.graphics.Paint$Join').ROUND.value);
 paint.setTypeface(Java.use('android.graphics.Typeface').create('sans-serif-medium',0));
 for(const c of commands){
  paint.setColor((0xff000000|parseInt((c.color||'#000000').slice(1),16))|0);paint.setStyle(Style.FILL.value);
  if(c.op==='roundRect')canvas.drawRoundRect(c.x,c.y,c.x+c.w,c.y+c.h,c.r,c.r,paint);
  if(c.op==='text'){paint.setTextSize(c.size);canvas.drawText(c.value,c.x,c.y,paint);}
  if(c.op==='path'){
   const path=Java.use('android.graphics.Path').$new();c.points.forEach((v,i)=>i?path.lineTo(v[0],v[1]):path.moveTo(v[0],v[1]));
   if(c.fill)path.close();else{paint.setStyle(Style.STROKE.value);paint.setStrokeWidth(c.width);}canvas.drawPath(path,paint);
  }
  if(c.op==='drawable'){
   const id=context.getResources().getIdentifier(c.name,'drawable','ru.yandex.yandexnavi');
   if(!id)throw new Error('Missing stock drawable: '+c.name);
   const drawable=context.getDrawable(id).mutate();
   drawable.setBounds(0,0,Math.round(c.w),Math.round(c.h));drawable.setAlpha(Math.round(c.alpha*255));
   const b=c.brightness;
   if(b!==1)drawable.setColorFilter(Java.use('android.graphics.ColorMatrixColorFilter').$new(Java.array('float',[b,0,0,0,0,0,b,0,0,0,0,0,b,0,0,0,0,0,1,0])));
   canvas.save();canvas.translate(c.x+c.w/2,c.y+c.h/2);canvas.rotate(c.rotation);canvas.translate(-c.w/2,-c.h/2);drawable.draw(canvas);canvas.restore();
  }
 }
}
