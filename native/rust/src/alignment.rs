use crate::stars::Star;

#[derive(Clone, Copy, Debug, Default)]
pub struct Transform { pub dx:f32, pub dy:f32, pub angle:f32, pub scale:f32, pub inliers:usize, pub error:f32 }

fn nearest(a:&Star, b:&[Star], max_dist:f32) -> Option<Star> {
    let mut best=None; let mut bd=max_dist*max_dist;
    for q in b { let dx=a.x-q.x; let dy=a.y-q.y; let d=dx*dx+dy*dy; if d<bd { bd=d; best=Some(*q); } }
    best
}

pub fn estimate(a:&[Star], b:&[Star], w:usize, h:usize) -> Transform {
    if a.len()<3 || b.len()<3 { return Transform::default(); }
    let radius=(w.min(h) as f32*0.08).max(8.0);
    let mut pairs=Vec::new();
    for p in a.iter().take(128) { if let Some(q)=nearest(p,b,radius) { pairs.push((*p,q)); } }
    if pairs.len()<3 { return Transform::default(); }
    // Deterministic RANSAC over single-pair translation hypotheses. Rotation/scale are
    // then estimated from the inlier set using centered least squares.
    let mut best=(0usize,0f32,0f32);
    for (pa,pb) in pairs.iter().take(64) {
        let dx=pa.x-pb.x; let dy=pa.y-pb.y; let mut n=0; let mut e=0f32;
        for (a,b) in &pairs { let ex=(a.x-b.x)-dx; let ey=(a.y-b.y)-dy; let d=(ex*ex+ey*ey).sqrt(); if d<radius*0.35 {n+=1;e+=d;} }
        if n>best.0 { best=(n,dx,dy); }
    }
    let mut inliers=Vec::new();
    for (a,b) in pairs { let ex=(a.x-b.x)-best.1; let ey=(a.y-b.y)-best.2; if (ex*ex+ey*ey).sqrt()<radius*0.35 { inliers.push((a,b)); } }
    if inliers.len()<3 { return Transform{dx:best.1,dy:best.2,scale:1.0,inliers:best.0,error:0.0,..Default::default()}; }
    let mut ax=0f32;let mut ay=0f32;let mut bx=0f32;let mut by=0f32;
    for (a,b) in &inliers {ax+=a.x;ay+=a.y;bx+=b.x;by+=b.y;} let n=inliers.len() as f32; ax/=n;ay/=n;bx/=n;by/=n;
    let mut c=0f32;let mut s=0f32;let mut den=0f32; let mut num_scale=0f32;
    for (a,b) in &inliers { let ux=b.x-bx;let uy=b.y-by;let vx=a.x-ax;let vy=a.y-ay; c+=ux*vx+uy*vy; s+=ux*vy-uy*vx; den+=ux*ux+uy*uy; num_scale+=(vx*vx+vy*vy).sqrt()*(ux*ux+uy*uy).sqrt(); }
    let angle=s.atan2(c); let scale=(num_scale/(den*n).max(1.0)).clamp(0.95,1.05);
    let ca=angle.cos()*scale; let sa=angle.sin()*scale;
    let tx=ax-(ca*bx-sa*by); let ty=ay-(sa*bx+ca*by);
    let mut err=0f32; for (a,b) in &inliers { let x=ca*b.x-sa*b.y+tx;let y=sa*b.x+ca*b.y+ty;err+=((a.x-x).powi(2)+(a.y-y).powi(2)).sqrt(); }
    Transform{dx:tx,dy:ty,angle,scale,inliers:inliers.len(),error:err/n}
}
