use std::collections::VecDeque;
use std::ffi::c_int;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct Star { pub x:f32, pub y:f32, pub flux:f32, pub pixels:u32 }

fn mean_std(data:&[u8], w:usize,h:usize,stride:usize)->(f32,f32){
    let mut n=0f64; let mut sum=0f64; let mut sq=0f64;
    for y in 0..h { for x in 0..w { let v=data[y*stride+x] as f64; sum+=v; sq+=v*v; n+=1.; }}
    if n==0. {return(0.,0.)} let m=sum/n; ((m as f32),((sq/n-m*m).max(0.).sqrt() as f32))
}

/// Detects compact bright connected components above mean + threshold_sigma*std.
/// This deliberately returns measured candidates only; it never creates synthetic stars.
#[no_mangle]
pub unsafe extern "C" fn sari_detect_stars(gray:*const u8,w:c_int,h:c_int,stride:c_int,threshold_sigma:f32,out:*mut Star,max_stars:c_int)->c_int{
    if gray.is_null()||out.is_null()||w<=2||h<=2||stride<w||max_stars<=0{return -1;}
    let s=std::slice::from_raw_parts(gray,(stride*h) as usize); let wu=w as usize; let hu=h as usize; let st=stride as usize;
    let (m,sd)=mean_std(s,wu,hu,st); let th=(m+threshold_sigma.max(0.5)*sd).min(250.);
    let mut seen=vec![false;wu*hu]; let mut count=0; let dst=std::slice::from_raw_parts_mut(out,max_stars as usize);
    for y in 1..hu-1 { for x in 1..wu-1 { let idx=y*wu+x; if seen[idx]||s[y*st+x] as f32<=th{continue;} let mut q=VecDeque::new();q.push_back((x,y));seen[idx]=true;let mut sx=0f64;let mut sy=0f64;let mut sf=0f64;let mut n=0u32;
        while let Some((cx,cy))=q.pop_front(){let v=s[cy*st+cx] as f32;let flux=(v-th).max(0.) as f64;sx+=cx as f64*flux;sy+=cy as f64*flux;sf+=flux;n+=1;
            for (nx,ny) in [(cx-1,cy),(cx+1,cy),(cx,cy-1),(cx,cy+1)]{let ni=ny*wu+nx;if !seen[ni]&&s[ny*st+nx] as f32>th{seen[ni]=true;q.push_back((nx,ny));}}
        }
        if n>=2 && n<=80 && sf>0. && count<max_stars as usize {
            // Reject isolated hot pixels/large saturated blobs; keep compact measured candidates.
            let cx=(sx/sf) as f32; let cy=(sy/sf) as f32;
            if cx>=1.0 && cy>=1.0 && cx<(wu-1) as f32 && cy<(hu-1) as f32 {
                dst[count]=Star{x:cx,y:cy,flux:sf as f32,pixels:n}; count+=1;
            }
        }
    }}
    count as c_int
}

#[no_mangle] pub extern "C" fn sari_astro_version()->*const std::ffi::c_char { b"SARI Astro Rust 1.0\0".as_ptr() as *const _ }


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_measured_bright_component() {
        let w = 32usize; let h = 32usize; let stride = 32usize;
        let mut img = vec![10u8; w*h];
        for y in 14..18 { for x in 14..18 { img[y*stride+x] = 250; } }
        let mut out = vec![Star{x:0.0,y:0.0,flux:0.0,pixels:0}; 16];
        let n = unsafe { sari_detect_stars(img.as_ptr(), w as c_int, h as c_int, stride as c_int, 3.0, out.as_mut_ptr(), 16) };
        assert!(n > 0);
        assert!((out[0].x - 15.5).abs() < 1.0);
        assert!((out[0].y - 15.5).abs() < 1.0);
    }

    #[test]
    fn empty_image_has_no_stars() {
        let img = vec![10u8; 32*32];
        let mut out = vec![Star{x:0.0,y:0.0,flux:0.0,pixels:0}; 16];
        let n = unsafe { sari_detect_stars(img.as_ptr(), 32, 32, 32, 3.0, out.as_mut_ptr(), 16) };
        assert_eq!(n, 0);
    }
}
