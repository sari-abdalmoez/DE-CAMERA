#include "sari_engine.h"
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/photo.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/calib3d.hpp>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <numeric>
#include <vector>

const char* sari_native_version(){ return "SARI Image Engine 2.0 / OpenCV 4.10.x"; }

float sari_estimate_blur(const uint8_t* gray,int w,int h,int stride){
    if(!gray||w<8||h<8) return 0.f;
    cv::Mat src(h,w,CV_8UC1,const_cast<uint8_t*>(gray),stride), lap;
    cv::Laplacian(src,lap,CV_64F);
    cv::Scalar m,s; cv::meanStdDev(lap,m,s);
    return static_cast<float>(s[0]*s[0]);
}

static void conservative_unsharp(const cv::Mat& src, cv::Mat& dst, float amount){
    cv::Mat blur;
    cv::GaussianBlur(src, blur, cv::Size(0,0), 0.8);
    cv::addWeighted(src, 1.0 + amount, blur, -amount, 0.0, dst);
}

static float haze_score(const cv::Mat& bgr){
    cv::Mat small; cv::resize(bgr,small,cv::Size(160,160),0,0,cv::INTER_AREA);
    std::vector<cv::Mat> ch; cv::split(small,ch);
    cv::Mat mn = ch[0].clone(); cv::min(mn,ch[1],mn); cv::min(mn,ch[2],mn);
    cv::Scalar mean = cv::mean(mn);
    return static_cast<float>(mean[0] / 255.0);
}

static void conservative_dehaze(const cv::Mat& bgr, cv::Mat& out, float strength){
    // Low-frequency veil removal. It intentionally avoids aggressive dark-channel inversion,
    // which can destroy faint stars and shadow detail.
    cv::Mat lab; cv::cvtColor(bgr,lab,cv::COLOR_BGR2Lab);
    std::vector<cv::Mat> c; cv::split(lab,c);
    cv::Mat low; cv::GaussianBlur(c[0],low,cv::Size(0,0),std::max(8.0, bgr.cols/80.0));
    cv::Mat local; cv::subtract(c[0],low,local,cv::noArray(),CV_16S);
    cv::Mat l8; c[0].convertTo(l8,CV_8U);
    cv::Ptr<cv::CLAHE> clahe=cv::createCLAHE(1.3,cv::Size(8,8));
    cv::Mat enhanced; clahe->apply(l8,enhanced);
    cv::addWeighted(l8,1.0-0.28*strength,enhanced,0.28*strength,0,c[0]);
    cv::Mat merged; cv::merge(c,merged); cv::cvtColor(merged,out,cv::COLOR_Lab2BGR);
}

int sari_process_rgba(const uint8_t* in,uint8_t* out,int w,int h,float strength){
    if(!in||!out||w<=0||h<=0) return -1;
    strength=std::clamp(strength,0.f,1.f);
    cv::Mat src(h,w,CV_8UC4,const_cast<uint8_t*>(in));
    cv::Mat bgr; cv::cvtColor(src,bgr,cv::COLOR_RGBA2BGR);

    // Estimate blur before sharpening so noise is not mistaken for detail.
    cv::Mat gray; cv::cvtColor(bgr,gray,cv::COLOR_BGR2GRAY);
    cv::Mat lap; cv::Laplacian(gray,lap,CV_64F); cv::Scalar lm,ls; cv::meanStdDev(lap,lm,ls);
    float blurMetric=float(ls[0]*ls[0]);

    // Real OpenCV non-local means: capped to preserve hair, fabric and skin texture.
    cv::Mat den;
    int hL=std::clamp(int(std::lround(2.0 + 5.0*strength)),2,7);
    cv::fastNlMeansDenoisingColored(bgr,den,hL,hL,7,17);

    // Chroma smoothing is stronger than luminance smoothing to reduce color noise without plastic detail.
    cv::Mat ycrcb; cv::cvtColor(den,ycrcb,cv::COLOR_BGR2YCrCb);
    std::vector<cv::Mat> yc; cv::split(ycrcb,yc);
    cv::GaussianBlur(yc[1],yc[1],cv::Size(0,0),0.6 + 0.9*strength);
    cv::GaussianBlur(yc[2],yc[2],cv::Size(0,0),0.6 + 0.9*strength);
    cv::merge(yc,ycrcb); cv::cvtColor(ycrcb,den,cv::COLOR_YCrCb2BGR);

    cv::Mat corrected=den;
    float haze=haze_score(den);
    if(haze>0.55f && strength>=0.45f){
        cv::Mat dh; conservative_dehaze(den,dh,std::min(1.f,(haze-0.5f)*2.0f));
        corrected=dh;
    }

    cv::Mat sharp;
    float sharpen = (blurMetric < 35.0 ? 0.10f : 0.035f) * strength;
    conservative_unsharp(corrected,sharp,sharpen);

    // Output safety gate: reject large global brightness excursions introduced by processing.
    cv::Scalar inMean = cv::mean(bgr), outMean = cv::mean(sharp);
    double inL = (inMean[0] + inMean[1] + inMean[2]) / 3.0;
    double outL = (outMean[0] + outMean[1] + outMean[2]) / 3.0;
    if (inL > 4.0 && (outL / inL > 1.22 || outL / inL < 0.78)) {
        cv::addWeighted(bgr, 0.72, sharp, 0.28, 0.0, sharp);
    }
    cv::Mat rgba; cv::cvtColor(sharp,rgba,cv::COLOR_BGR2RGBA);
    std::memcpy(out,rgba.data,(size_t)w*h*4); return 0;
}

static cv::Mat align_frame(const cv::Mat& refGray,const cv::Mat& src, bool& ok){
    cv::Mat gray; cv::cvtColor(src,gray,cv::COLOR_RGBA2GRAY);
    cv::Ptr<cv::ORB> orb=cv::ORB::create(900,1.2f,8,15,0,2,cv::ORB::HARRIS_SCORE,15,10);
    std::vector<cv::KeyPoint> k1,k2; cv::Mat d1,d2; orb->detectAndCompute(refGray,cv::noArray(),k1,d1); orb->detectAndCompute(gray,cv::noArray(),k2,d2);
    if(d1.empty()||d2.empty()){ok=false;return{};}
    cv::BFMatcher matcher(cv::NORM_HAMMING,false); std::vector<std::vector<cv::DMatch>> knn; matcher.knnMatch(d2,d1,knn,2);
    std::vector<cv::Point2f> p2,p1; for(auto&m:knn) if(m.size()==2 && m[0].distance < 0.72f*m[1].distance){p2.push_back(k2[m[0].queryIdx].pt);p1.push_back(k1[m[0].trainIdx].pt);}
    if(p1.size()<8){ok=false;return{};}
    cv::Mat mask; cv::Mat A=cv::estimateAffinePartial2D(p2,p1,mask,cv::RANSAC,2.0,0.995,3000,10);
    int inliers=mask.empty()?0:cv::countNonZero(mask); ok=!A.empty()&&inliers>=7&&inliers>=int(p1.size()*0.25f);
    if(!ok)return{};
    cv::Mat warped; cv::warpAffine(src,warped,A,src.size(),cv::INTER_LINEAR,cv::BORDER_REFLECT); return warped;
}

static float median_small(std::vector<float>& v){
    if(v.empty())return 0.f; size_t n=v.size()/2; std::nth_element(v.begin(),v.begin()+n,v.end()); float m=v[n]; if(v.size()%2==0){std::nth_element(v.begin(),v.begin()+n-1,v.end());m=0.5f*(m+v[n-1]);} return m;
}

int sari_stack_rgba(const uint8_t* const* frames,int count,uint8_t* out,int w,int h,float sigma){
    if(!frames||!out||count<2||w<=0||h<=0) return -1;
    sigma=std::clamp(sigma,1.0f,4.0f);
    cv::Mat ref(h,w,CV_8UC4,const_cast<uint8_t*>(frames[0]));
    cv::Mat refGray; cv::cvtColor(ref,refGray,cv::COLOR_RGBA2GRAY);
    cv::Scalar refMean, refStd; cv::meanStdDev(refGray,refMean,refStd);
    std::vector<cv::Mat> aligned; aligned.reserve(count); aligned.push_back(ref.clone());
    for(int i=1;i<count;i++){
        if(!frames[i])continue;
        cv::Mat src(h,w,CV_8UC4,const_cast<uint8_t*>(frames[i])); cv::Mat g; cv::cvtColor(src,g,cv::COLOR_RGBA2GRAY);
        cv::Scalar gm,gs; cv::meanStdDev(g,gm,gs);
        if(refMean[0] > 3.0 && (gm[0] < refMean[0]*0.35 || gm[0] > refMean[0]*2.8)) continue;
        cv::Mat gl; cv::Laplacian(g,gl,CV_64F); cv::Scalar glM,glS; cv::meanStdDev(gl,glM,glS);
        cv::Mat rl; cv::Laplacian(refGray,rl,CV_64F); cv::Scalar rlM,rlS; cv::meanStdDev(rl,rlM,rlS);
        if(rlS[0] > 2.0 && glS[0] < rlS[0]*0.28) continue;
        bool ok=false; cv::Mat a=align_frame(refGray,src,ok); if(ok)aligned.push_back(std::move(a));
    }
    if(aligned.size()<2)return -2;

    cv::Mat result(h,w,CV_8UC4);
    std::vector<float> vals; vals.reserve(aligned.size());
    std::vector<float> dev; dev.reserve(aligned.size());
    for(int y=0;y<h;y++){
        auto* dst=result.ptr<cv::Vec4b>(y);
        for(int x=0;x<w;x++){
            cv::Vec4b q;
            for(int c=0;c<4;c++){
                vals.clear(); for(auto&im:aligned) vals.push_back(im.at<cv::Vec4b>(y,x)[c]);
                float med=median_small(vals); dev.clear(); for(float v:vals)dev.push_back(std::abs(v-med)); float mad=median_small(dev); float limit=std::max(2.0f,sigma*1.4826f*mad);
                float sum=0.f; int n=0; for(float v:vals) if(std::abs(v-med)<=limit){sum+=v;n++;}
                q[c]=static_cast<uint8_t>(std::clamp(sum/std::max(1,n),0.f,255.f));
            }
            dst[x]=q;
        }
    }
    std::memcpy(out,result.data,(size_t)w*h*4); return (int)aligned.size();
}
