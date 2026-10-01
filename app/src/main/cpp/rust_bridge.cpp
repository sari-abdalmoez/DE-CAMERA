#include <cstdint>
extern "C" {
struct SariStar { float x,y,flux; unsigned pixels; };
int sari_detect_stars(const uint8_t*,int,int,int,float,SariStar*,int);
}
