#[derive(Clone, Copy, Debug)]
pub struct Star { pub x: f32, pub y: f32, pub strength: f32 }

fn median(values: &mut [f32]) -> f32 {
    if values.is_empty() { return 0.0; }
    values.sort_by(|a,b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    values[values.len()/2]
}

pub fn detect(img: &[u8], w: usize, h: usize, sigma_k: f32, max_points: usize) -> Vec<Star> {
    if w < 5 || h < 5 || img.len() < w*h { return Vec::new(); }
    let step = ((w*h) / 1_500_000).max(1).min(4);
    let mut sample = Vec::with_capacity((w/step).saturating_mul(h/step));
    for y in (1..h-1).step_by(step) { for x in (1..w-1).step_by(step) { sample.push(img[y*w+x] as f32); } }
    let med = median(&mut sample);
    let mut dev: Vec<f32> = sample.into_iter().map(|v| (v-med).abs()).collect();
    let mad = median(&mut dev).max(0.5);
    let noise = (1.4826 * mad).max(1.0);
    let threshold = med + sigma_k.max(1.5) * noise;
    let mut stars = Vec::new();
    for y in (2..h-2).step_by(step) {
        for x in (2..w-2).step_by(step) {
            let i=y*w+x; let v=img[i] as f32;
            if v < threshold { continue; }
            let mut peak=true; let mut hot=false;
            for dy in -1i32..=1 { for dx in -1i32..=1 {
                if dx==0 && dy==0 { continue; }
                let q=((y as i32+dy) as usize)*w+(x as i32+dx) as usize;
                if img[q] as f32 >= v { peak=false; }
            }}
            // Isolated single-pixel saturation is more likely a hot pixel than a star.
            if v >= 250.0 {
                let mut bright=0; for dy in -1i32..=1 { for dx in -1i32..=1 {
                    if img[((y as i32+dy) as usize)*w+(x as i32+dx) as usize] as f32 > threshold { bright+=1; }
                }}
                hot = bright <= 2;
            }
            if !peak || hot { continue; }
            let mut sx=0f32; let mut sy=0f32; let mut sw=0f32;
            for dy in -2i32..=2 { for dx in -2i32..=2 {
                let xx=(x as i32+dx) as usize; let yy=(y as i32+dy) as usize;
                let weight=(img[yy*w+xx] as f32-med).max(0.0);
                sx += xx as f32*weight; sy += yy as f32*weight; sw += weight;
            }}
            if sw > 1.0 { stars.push(Star{x:sx/sw,y:sy/sw,strength:(v-med)/noise}); }
            if stars.len() >= max_points { return stars; }
        }
    }
    stars.sort_by(|a,b| b.strength.partial_cmp(&a.strength).unwrap_or(std::cmp::Ordering::Equal));
    stars.truncate(max_points);
    stars
}
