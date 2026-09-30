mod stars;
mod alignment;
mod stacking;
use std::slice;

#[no_mangle]
pub extern "C" fn sari_detect_stars(input:*const u8,len:usize,w:u32,h:u32,threshold:f32,out:*mut f32,max_points:usize)->usize {
    if input.is_null()||out.is_null()||w==0||h==0{return 0}
    let img=unsafe{slice::from_raw_parts(input,len)};
    let pts=stars::detect(img,w as usize,h as usize,threshold,max_points.min(2048));
    for (i,p) in pts.iter().enumerate(){unsafe{*out.add(i*2)=p.x;*out.add(i*2+1)=p.y;}}
    pts.len()
}

#[no_mangle]
pub extern "C" fn sari_align_stack(frames:*const *const u8,lens:*const usize,count:usize,w:u32,h:u32,sigma:f32,out:*mut u8)->i32 {
    if frames.is_null()||lens.is_null()||out.is_null()||count==0||w==0||h==0{return -1}
    let mut fs=Vec::with_capacity(count);
    for i in 0..count { unsafe { let p=*frames.add(i); let l=*lens.add(i); if p.is_null()||l<(w as usize*h as usize){return -2}; fs.push(slice::from_raw_parts(p,l)); } }
    let result=stacking::align_stack(&fs,w as usize,h as usize,sigma);
    unsafe{std::ptr::copy_nonoverlapping(result.as_ptr(),out,result.len());}
    0
}

#[no_mangle]
pub extern "C" fn sari_enhance(input:*const u8,out:*mut u8,w:u32,h:u32,profile:u32){
    if input.is_null()||out.is_null(){return}
    let w=w as usize; let h=h as usize; let n=w.saturating_mul(h);
    let a=unsafe{slice::from_raw_parts(input,n)}; let o=unsafe{slice::from_raw_parts_mut(out,n)};
    if n==0{return}
    // Real offline int8 CNN: 1->8->1 residual denoiser. It is deliberately evaluated on a
    // bounded-resolution pyramid so a 12MP frame never becomes an unbounded AI allocation.
    const W1:[i8;72]=[9,-77,41,40,-69,-19,-75,33,-35,90,91,13,-55,-76,-14,-36,57,-110,121,-101,99,-22,66,50,5,24,98,33,68,-100,92,44,33,18,63,118,-29,-72,-32,-78,62,-64,-69,111,118,15,-22,-17,61,-120,-105,103,127,-8,-102,4,-60,0,63,57,-15,14,35,-101,-44,5,-73,-56,43,74,2,-50];
    const B1:[i8;8]=[74,-74,-32,127,25,9,45,-33];
    const W2:[i8;72]=[111,-106,-36,-22,-53,-32,81,71,7,113,-92,-78,59,-7,-118,-90,-79,100,59,101,-97,-127,-85,14,-26,-82,95,-42,-75,60,-35,83,31,3,27,-98,-70,-61,-74,23,26,-54,85,-72,26,-37,-113,-85,53,51,-80,-41,-122,70,112,43,-80,69,108,-27,-21,45,17,-43,-121,-91,7,-7,107,4,-33,-127];
    const B2:i8=-127;
    const S1:f32=0.0025911817; const SB1:f32=0.0024760377; const S2:f32=0.0009067058; const SB2:f32=0.0008531943;
    let max_ai=match profile {2=>750_000usize,1=>600_000usize,_=>450_000usize};
    let factor=((n as f32/max_ai as f32).sqrt().ceil() as usize).max(1).min(4);
    let aw=(w+factor-1)/factor; let ah=(h+factor-1)/factor; let an=aw*ah;
    let mut hidden=vec![0f32;an*8];
    for y in 0..ah { for x in 0..aw {
        let sx=(x*factor).min(w-1); let sy=(y*factor).min(h-1);
        let mut patch=[0f32;9]; let mut k=0;
        for dy in -1i32..=1 { for dx in -1i32..=1 { let xx=(sx as i32+dx).clamp(0,w as i32-1) as usize; let yy=(sy as i32+dy).clamp(0,h as i32-1) as usize; patch[k]=a[yy*w+xx] as f32/255.0; k+=1; }}
        for ch in 0..8 { let base=ch*9; let mut sum=B1[ch] as f32*SB1; for j in 0..9 {sum+=patch[j]*(W1[base+j] as f32*S1);} hidden[(y*aw+x)*8+ch]=sum.max(0.0); }
    }}
    for y in 0..h { for x in 0..w {
        let fx=x as f32/factor as f32; let fy=y as f32/factor as f32; let _x0=fx.floor() as usize; let _y0=fy.floor() as usize;
        let mut sum=B2 as f32*SB2;
        let cx=fx.round() as isize; let cy=fy.round() as isize;
        for ch in 0..8 { for dy in -1isize..=1 { for dx in -1isize..=1 {
            let xx=(cx+dx).clamp(0,aw as isize-1) as usize; let yy=(cy+dy).clamp(0,ah as isize-1) as usize;
            sum += hidden[(yy*aw+xx)*8+ch]*(W2[ch*9+((dy+1)*3+(dx+1)) as usize] as f32*S2);
        }}}
        let base=a[y*w+x] as f32/255.0; let residual=(sum*0.30).clamp(-0.25,0.25); let strength=match profile{2=>1.0,1=>0.75,_=>0.55}; o[y*w+x]=((base+residual*strength).clamp(0.0,1.0)*255.0).round() as u8;
    }}
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn star_detector_finds_bright_point() {
        let w=32usize; let h=32usize; let mut img=vec![20u8;w*h];
        img[16*w+16]=255; img[16*w+15]=220; img[16*w+17]=220; img[15*w+16]=220; img[17*w+16]=220;
        let p=crate::stars::detect(&img,w,h,3.0,32); assert!(!p.is_empty());
    }
    #[test]
    fn stack_keeps_dimensions() {
        let w=16usize;let h=16usize;let a=vec![30u8;w*h];let b=vec![32u8;w*h];let r=crate::stacking::align_stack(&[&a,&b],w,h,2.5);assert_eq!(r.len(),w*h);
    }
}
