package com.zaknong.airus.ui;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.zaknong.airus.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'airus_engine' library on application startup.
    static {
        System.loadLibrary("airus_engine");
    }

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Example of a call to a native method
        TextView tv = binding.sampleText;
        tv.setText(stringFromJNI());
    }

    /**
     * A native method that is implemented by the 'airus_engine' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
}
