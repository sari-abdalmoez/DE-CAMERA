use crate::{alignment, stars};

fn bilinear(img:&[u8], w:usize,h:usize,x:f32,y:f32)->u8 {
    if x<0.0||y<0.0||x>=(w-1) as f32||y>=(h-1) as f32{return 0}
    let x0=x.floor() as usize; let y0=y.floor() as usize; let fx=x-x0 as f32;let fy=y-y0 as f32;
    let a=img[y0*w+x0] as f32;let b=img[y0*w+x0+1] as f32;let c=img[(y0+1)*w+x0] as f32;let d=img[(y0+1)*w+x0+1] as f32;
    (a*(1.0-fx)*(1.0-fy)+b*fx*(1.0-fy)+c*(1.0-fx)*fy+d*fx*fy).round().clamp(0.0,255.0) as u8
}

pub fn align_stack(frames:&[&[u8]],w:usize,h:usize,sigma:f32)->Vec<u8>{
    if frames.is_empty(){return vec![0;w*h]}
    let reference=stars::detect(frames[0],w,h,3.0,512);
    let mut aligned=Vec::with_capacity(frames.len());
    for (idx,f) in frames.iter().enumerate() {
        if f.len()<w*h {continue}
        if idx==0 {aligned.push(frames[0].to_vec());continue;}
        let pts=stars::detect(f,w,h,3.0,512);
        let t=alignment::estimate(&reference,&pts,w,h);
        if t.inliers<3 || t.error>(w.min(h) as f32*0.025) {continue;}
        let mut out=vec![0u8;w*h]; let ca=(t.angle.cos()*t.scale);let sa=(t.angle.sin()*t.scale);
        for y in 0..h { for x in 0..w {
            let xx=x as f32;let yy=y as f32;
            // inverse transform from reference output into source frame
            let sx=(ca*(xx-t.dx)+sa*(yy-t.dy)); let sy=(-sa*(xx-t.dx)+ca*(yy-t.dy));
            out[y*w+x]=bilinear(f,w,h,sx,sy);
        }}
        aligned.push(out);
    }
    if aligned.is_empty(){return vec![0;w*h]}
    // Sigma clipping per pixel. Frame count is bounded by Kotlin profile, keeping memory bounded.
    let n=aligned.len(); let mut out=vec![0u8;w*h];
    for i in 0..w*h {
        let mut vals=Vec::with_capacity(n); for f in &aligned { vals.push(f[i] as f32); }
        vals.sort_by(|a,b|a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        if n<4 {out[i]=vals.iter().sum::<f32>().div_euclid(n as f32).round() as u8;continue;}
        let lo=(n as f32*0.10).floor() as usize; let hi=(n as f32*0.90).ceil().min(n);
        let core=&vals[lo.min(hi-1)..hi.max(lo+1)];
        let mean=core.iter().sum::<f32>()/core.len() as f32;
        let var=core.iter().map(|v|(v-mean)*(v-mean)).sum::<f32>()/core.len() as f32;
        let sd=var.sqrt().max(1.0); let k=sigma.max(1.0);
        let mut sum=0f32;let mut count=0usize;
        for v in vals { if (v-mean).abs()<=k*sd {sum+=v;count+=1;} }
        out[i]=(sum/(count.max(1) as f32)).round().clamp(0.0,255.0) as u8;
    }
    out
}
