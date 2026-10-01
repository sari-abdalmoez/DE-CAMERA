package com.sari.camera

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity

class ViewerActivity:ComponentActivity(){
 private lateinit var image:ImageView; private lateinit var uri:Uri
 override fun onCreate(b:Bundle?){super.onCreate(b);uri=intent.data?:return;val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};image=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;adjustViewBounds=true};root.addView(image,LinearLayout.LayoutParams(-1,0,1f));val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};bar.addView(Button(this).apply{text="SHARE";setOnClickListener{startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=contentResolver.getType(uri)?:"image/*";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share"))}});bar.addView(Button(this).apply{text="DELETE";setOnClickListener{contentResolver.delete(uri,null,null);finish()}});bar.addView(Button(this).apply{text="METADATA";setOnClickListener{Toast.makeText(this@ViewerActivity,intent.getStringExtra("name")?:"image",Toast.LENGTH_LONG).show()}});root.addView(bar);setContentView(root);load()}
 private fun load(){try{contentResolver.openInputStream(uri)?.use{image.setImageBitmap(BitmapFactory.decodeStream(it))}}catch(_:Exception){startActivity(Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}}
}
